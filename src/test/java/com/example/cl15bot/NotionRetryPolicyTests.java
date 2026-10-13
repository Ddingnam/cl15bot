package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class NotionRetryPolicyTests {
    private final AtomicLong now = new AtomicLong();
    private final NotionRetryPolicy policy = new NotionRetryPolicy(now::get, now::addAndGet);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.notion.com/v1")
            .requestInterceptor(policy);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient client = builder.build();

    @ParameterizedTest
    @ValueSource(strings = {"body", "metadata"})
    void broken429ResponseAlwaysClosesWithoutRetrying(String failure) {
        var closed = new AtomicBoolean();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var response = new org.springframework.http.client.ClientHttpResponse() {
            public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(429); }
            public String getStatusText() throws IOException {
                if (failure.equals("metadata")) throw new IOException("disconnected metadata");
                return "Too Many Requests";
            }
            public org.springframework.http.HttpHeaders getHeaders() { return new org.springframework.http.HttpHeaders(); }
            public InputStream getBody() throws IOException {
                if (failure.equals("body")) throw new IOException("disconnected body");
                return InputStream.nullInputStream();
            }
            public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> policy.intercept(new org.springframework.mock.http.client.MockClientHttpRequest(
                org.springframework.http.HttpMethod.POST, URI.create("https://api.notion.com/v1/pages")), new byte[0],
                (request, body) -> { attempts.incrementAndGet(); return response; }));
        assertTrue(closed.get());
        assertEquals(1, attempts.get());
    }

    @Test void rateLimitWaitCannotCrossTheMattermostProcessingDeadline() {
        Instant received = Instant.parse("2026-10-13T12:00:00Z");
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return received.plusMillis(now.get()); }
        };
        now.set(Duration.ofMinutes(24).plusSeconds(30).toMillis());
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)).header("Retry-After", "60"));
        assertThrows(MattermostRequestDeadline.ExpiredException.class,
                () -> MattermostRequestDeadline.within(clock, received,
                        () -> policy.withinBudget(() -> client.post().uri("/pages").retrieve().toBodilessEntity())));
        assertEquals(Duration.ofMinutes(24).plusSeconds(30).toMillis(), now.get());
        assertDoesNotThrow(MattermostRequestDeadline::check);
        server.verify();
    }

    @Test void explicitlyRejectedCreateWaitsAndRetriesOnlyTheFailedRequest() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(content().string("same-body"))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)).header("Retry-After", "5")
                        .body("{\"code\":\"rate_limited\"}").contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(content().string("same-body")).andRespond(withSuccess("saved", MediaType.TEXT_PLAIN));
        assertEquals("saved", policy.withinBudget(() -> client.post().uri("/pages").body("same-body")
                .retrieve().body(String.class)));
        assertEquals(5_000, now.get());
        server.verify();
    }

    @Test void exhausted429HasTwoRetriesAndPreservesOriginalHeadersAndBody() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo("https://api.notion.com/v1/pages"))
                    .andRespond(withStatus(HttpStatusCode.valueOf(429)).header("Retry-After", "2")
                            .body("{\"code\":\"rate_limited\"}").contentType(MediaType.APPLICATION_JSON));
        }
        var error = assertThrows(RestClientResponseException.class,
                () -> client.post().uri("/pages").retrieve().toBodilessEntity());
        assertEquals(429, error.getStatusCode().value());
        assertEquals("2", error.getResponseHeaders().getFirst("Retry-After"));
        assertTrue(error.getResponseBodyAsString().contains("rate_limited"));
        assertEquals(4_000, now.get());
        server.verify();
    }

    @Test void retryBudgetIsSharedAcrossMultipleStagesOfOneRecord() {
        for (String stage : new String[] {"/data_sources/members/query", "/data_sources/study"}) {
            server.expect(requestTo("https://api.notion.com/v1" + stage))
                    .andRespond(withStatus(HttpStatusCode.valueOf(429)).header("Retry-After", "70"));
            if (stage.endsWith("query")) {
                server.expect(requestTo("https://api.notion.com/v1" + stage))
                        .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
            }
        }
        policy.withinBudget(() -> {
            client.post().uri("/data_sources/members/query").retrieve().toBodilessEntity();
            assertThrows(RestClientResponseException.class,
                    () -> client.get().uri("/data_sources/study").retrieve().toBodilessEntity());
            return null;
        });
        assertEquals(70_000, now.get());
        server.verify();
    }

    @Test void longCooldownStopsAnotherRequestBeforeSendingEvenWithAFreshBudget() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)).header("Retry-After", "180"));
        assertThrows(RestClientResponseException.class, () -> client.post().uri("/pages").retrieve().toBodilessEntity());
        assertThrows(NotionRetryPolicy.CoolingDownException.class,
                () -> client.get().uri("/data_sources/study").retrieve().toBodilessEntity());
        assertEquals(0, now.get());
        server.verify();
    }

    @Test void anotherRecordWaitsForTheCooldownLeftByAnExhaustedRequest() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo("https://api.notion.com/v1/pages"))
                    .andRespond(withStatus(HttpStatusCode.valueOf(429)).header("Retry-After", "5"));
        }
        server.expect(requestTo("https://api.notion.com/v1/data_sources/study"))
                .andExpect(request -> assertEquals(15_000, now.get())).andRespond(withSuccess());
        assertThrows(RestClientResponseException.class,
                () -> policy.withinBudget(() -> client.post().uri("/pages").retrieve().toBodilessEntity()));
        policy.withinBudget(() -> client.get().uri("/data_sources/study").retrieve().toBodilessEntity());
        server.verify();
    }

    @Test void apiAccessBlockedIsNotRetried() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)).header("Retry-After", "5")
                        .body("{\"additional_data\":{\"rate_limit_reason\":\"public_api_request_blocked\"}}"));
        assertThrows(RestClientResponseException.class, () -> client.post().uri("/pages").retrieve().toBodilessEntity());
        assertEquals(0, now.get());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 502, 503, 504, 529})
    void creationErrorsOtherThan429NeverRetry(int status) {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)));
        assertThrows(RestClientResponseException.class, () -> client.post().uri("/pages").retrieve().toBodilessEntity());
        assertEquals(0, now.get());
        server.verify();
    }

    @Test void uncertainCreationNetworkFailureNeverRetries() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withException(new IOException("uncertain")));
        assertThrows(RestClientException.class, () -> client.post().uri("/pages").retrieve().toBodilessEntity());
        assertEquals(0, now.get());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 529})
    void readOnlyPostQueriesRetryTransientServerErrors(int status) {
        server.expect(requestTo("https://api.notion.com/v1/data_sources/members/query"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/members/query"))
                .andRespond(withSuccess());
        client.post().uri("/data_sources/members/query").retrieve().toBodilessEntity();
        assertEquals(1_000, now.get());
        server.verify();
    }

    @Test void readOnlyNetworkFailureRetries() {
        server.expect(requestTo("https://api.notion.com/v1/data_sources/study"))
                .andRespond(withException(new IOException("query-failure")));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/study")).andRespond(withSuccess());
        client.get().uri("/data_sources/study").retrieve().toBodilessEntity();
        assertEquals(1_000, now.get());
        server.verify();
    }
}
