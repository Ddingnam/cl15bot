package com.example.cl15bot;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MattermostResponseSender {
    private static final Logger log = LoggerFactory.getLogger(MattermostResponseSender.class);
    private final RestClient client;

    public MattermostResponseSender() {
        this(clientBuilder().build());
    }

    MattermostResponseSender(RestClient client) {
        this.client = client;
    }

    static RestClient.Builder clientBuilder() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory);
    }

    static URI responseUri(String url) {
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getRawQuery() != null || uri.getPath() == null
                || !uri.getPath().matches("(?:/[^/]+)*/hooks/commands/[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Invalid Mattermost response URL");
        }
        return uri;
    }

    static URI responseUri(String url, String baseUrl) {
        URI uri = responseUri(url);
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("Mattermost base URL missing");
        URI base = URI.create(baseUrl);
        if (base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
                || base.getFragment() != null || !uri.getScheme().equalsIgnoreCase(base.getScheme())
                || !uri.getHost().equalsIgnoreCase(base.getHost()) || port(uri) != port(base)) {
            throw new IllegalArgumentException("Untrusted Mattermost response origin");
        }
        String prefix = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        if (!uri.getPath().matches(java.util.regex.Pattern.quote(prefix) + "/hooks/commands/[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Untrusted Mattermost response path");
        }
        return uri;
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public void send(URI uri, StudyCommandController.CommandResponse response) {
        try {
            int status = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(response)
                    .exchange((request, result) -> result.getStatusCode().value());
            if (status >= 200 && status < 300) {
                log.info("Study request: requestId={} stage=delivery-completed HTTP={}", MDC.get("studyRequestId"), status);
            } else {
                log.warn("Mattermost final response delivery failed: requestId={} HTTP={}. Check Notion before resubmitting.",
                        MDC.get("studyRequestId"), status);
            }
        } catch (RestClientException ex) {
            // Callback URLs contain credentials. Never log the URL or exception message.
            log.warn("Mattermost final response delivery failed: requestId={} cause={}. Check Notion before resubmitting.",
                    MDC.get("studyRequestId"), ex.getClass().getSimpleName());
        }
    }
}
