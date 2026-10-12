package com.example.cl15bot;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NotionStudyClient {
    private final BotProperties properties;
    private final RestClient client;

    public NotionStudyClient(BotProperties properties, RestClient notionRestClient) {
        this.properties = properties;
        this.client = notionRestClient;
    }

    public void save(StudyEntry entry, String memberPageId) {
        Map<String, Object> fields = Map.of(
                properties.notionTitleProperty(), Map.of("title", richText(entry.subject())),
                "날짜", Map.of("date", Map.of("start", entry.date().toString())),
                "이름", Map.of("relation", List.of(Map.of("id", memberPageId))),
                "시작 시간", Map.of("date", Map.of("start", entry.startTime().toOffsetDateTime().toString())),
                "종료 시간", Map.of("date", Map.of("start", entry.endTime().toOffsetDateTime().toString())));
        client.post().uri("/pages")
                .body(Map.of("parent", Map.of("type", "data_source_id",
                        "data_source_id", properties.notionDataSourceId()), "properties", fields))
                .retrieve().toBodilessEntity();
    }

    private static List<Map<String, Object>> richText(String content) {
        return List.of(Map.of("type", "text", "text", Map.of("content", content)));
    }
}
