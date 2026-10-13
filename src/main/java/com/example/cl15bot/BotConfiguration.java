package com.example.cl15bot;

import java.time.Clock;
import java.time.ZoneId;
import java.time.Duration;
import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class BotConfiguration {
    @Bean
    RestClient notionRestClient(BotProperties properties) {
        return notionClientBuilder(properties).build();
    }

    static RestClient.Builder notionClientBuilder(BotProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory)
                .baseUrl("https://api.notion.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.notionToken())
                .defaultHeader("Notion-Version", "2025-09-03");
    }

    @Bean
    Clock studyClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
