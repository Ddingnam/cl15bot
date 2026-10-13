package com.example.cl15bot;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public void send(URI uri, StudyCommandController.CommandResponse response) {
        try {
            client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(response)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            // Callback URLs contain credentials. Never log the URL or exception message.
            log.warn("Mattermost final response delivery failed: cause={}. Check Notion before resubmitting.",
                    ex.getClass().getSimpleName());
        }
    }
}
