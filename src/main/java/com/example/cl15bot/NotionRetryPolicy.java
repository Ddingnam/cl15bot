package com.example.cl15bot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** One shared cooldown; a record's HTTP stages share a two-minute retry deadline. */
@Component
public class NotionRetryPolicy implements ClientHttpRequestInterceptor {
    private static final Logger log = LoggerFactory.getLogger(NotionRetryPolicy.class);
    static final long BUDGET_MILLIS = 120_000;
    private final LongSupplier time;
    private final Sleeper sleeper;
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private final Object cooldownLock = new Object();
    private long cooldownUntil;

    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }

    public NotionRetryPolicy() {
        this(() -> System.nanoTime() / 1_000_000, Thread::sleep);
    }

    NotionRetryPolicy(LongSupplier time, Sleeper sleeper) {
        this.time = time;
        this.sleeper = sleeper;
    }

    public <T> T withinBudget(Supplier<T> operation) {
        Long previous = deadline.get();
        if (previous == null) deadline.set(time.getAsLong() + BUDGET_MILLIS);
        try { return operation.get(); }
        finally { if (previous == null) deadline.remove(); }
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Long operationDeadline = deadline.get();
        long end = operationDeadline == null ? time.getAsLong() + BUDGET_MILLIS : operationDeadline;
        String path = request.getURI().getPath();
        boolean read = request.getMethod() == HttpMethod.GET
                || request.getMethod() == HttpMethod.POST && path.matches("/v1/data_sources/[^/]+/query");
        String stage = path.endsWith("/query") ? "query" : read ? "schema" : "study-save";
        for (int attempt = 0; ; attempt++) {
            awaitCooldown(end);
            MattermostRequestDeadline.check();
            ClientHttpResponse response;
            try { response = execution.execute(request, body); }
            catch (IOException ex) {
                if (!read || attempt >= 2 || !canWait(1_000L << attempt, end)) throw ex;
                if (!MattermostRequestDeadline.allowsWait(1_000L << attempt)) {
                    throw new MattermostRequestDeadline.ExpiredException();
                }
                pause(1_000L << attempt);
                retryLog(stage, attempt + 1, "network", 1_000L << attempt);
                continue;
            }
            int status = response.getStatusCode().value();
            boolean blocked = false;
            if (status == 429) {
                // Preserve the body so exhausted requests still become the original HTTP exception.
                byte[] content;
                BufferedResponse buffered;
                try {
                    content = response.getBody().readAllBytes();
                    buffered = new BufferedResponse(response.getStatusCode(), response.getStatusText(),
                            HttpHeaders.readOnlyHttpHeaders(response.getHeaders()), content);
                } finally {
                    response.close();
                }
                response = buffered;
                blocked = new String(content, StandardCharsets.UTF_8).contains("public_api_request_blocked");
            }
            boolean limited = status == 429 && !blocked || status == 529 && read;
            boolean retryRead = read && (status == 500 || status == 502 || status == 503 || status == 504);
            if (!limited && !retryRead) return response;
            long delay = retryDelay(response.getHeaders(), attempt);
            if (limited) extendCooldown(delay);
            if (attempt >= 2 || !canWait(delay, end)) return response;
            response.close();
            if (!MattermostRequestDeadline.allowsWait(delay)) {
                throw new MattermostRequestDeadline.ExpiredException();
            }
            if (!limited) pause(delay);
            retryLog(stage, attempt + 1, Integer.toString(status), delay);
        }
    }

    private void retryLog(String stage, int attempt, String cause, long delay) {
        log.info("Notion retry scheduled: requestId={} stage={} retry={} cause={} waitMs={}",
                MDC.get("studyRequestId"), stage, attempt, cause, delay);
    }

    private boolean canWait(long delay, long end) {
        return delay >= 0 && delay < Math.max(0, end - time.getAsLong());
    }

    private void extendCooldown(long delay) {
        long now = time.getAsLong();
        long until = delay > Long.MAX_VALUE - Math.max(0, now) ? Long.MAX_VALUE : now + delay;
        synchronized (cooldownLock) { cooldownUntil = Math.max(cooldownUntil, until); }
    }

    private void awaitCooldown(long end) throws IOException {
        while (true) {
            MattermostRequestDeadline.check();
            long remaining;
            synchronized (cooldownLock) { remaining = cooldownUntil - time.getAsLong(); }
            if (remaining <= 0) return;
            if (!MattermostRequestDeadline.allowsWait(remaining)) {
                throw new MattermostRequestDeadline.ExpiredException();
            }
            if (!canWait(remaining, end)) throw new CoolingDownException((remaining - 1) / 1_000 + 1);
            // Recheck the shared deadline in case another worker extends the cooldown.
            pause(Math.min(remaining, 1_000));
        }
    }

    private void pause(long millis) throws IOException {
        try { sleeper.sleep(millis); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Notion retry interrupted", ex);
        }
    }

    private long retryDelay(HttpHeaders headers, int attempt) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value != null) {
            try {
                long seconds = Long.parseLong(value.strip());
                if (seconds >= 0) return seconds > Long.MAX_VALUE / 1_000 ? Long.MAX_VALUE : seconds * 1_000;
            } catch (NumberFormatException ignored) {
                try {
                    return Math.max(0, Duration.between(ZonedDateTime.now(),
                            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis());
                } catch (RuntimeException invalidDate) { /* bounded fallback below */ }
            }
        }
        return 1_000L << Math.min(attempt, 2);
    }

    public static class CoolingDownException extends RestClientException {
        public CoolingDownException(long seconds) {
            super("Notion 요청 제한으로 자동 대기 한도를 초과해 저장하지 않았습니다. 약 " + seconds
                    + "초 후 같은 명령어로 다시 시도해주세요.");
        }
    }

    private record BufferedResponse(HttpStatusCode status, String statusText, HttpHeaders headers, byte[] body)
            implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() { return status; }
        @Override public String getStatusText() { return statusText; }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() { }
    }
}
