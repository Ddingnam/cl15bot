package com.example.cl15bot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public Optional<String> findMemberPageId(String memberName) {
        MemberQuery response = client.post()
                .uri("/data_sources/{id}/query", properties.notionMembersDataSourceId())
                .body(Map.of("filter", Map.of("property", "title",
                        "title", Map.of("equals", memberName)), "page_size", 2))
                .retrieve().body(MemberQuery.class);
        if (response == null || response.results() == null) {
            throw new org.springframework.web.client.RestClientException("Invalid Members query response");
        }
        if (response.has_more() || response.results().size() > 1) {
            throw new MemberRegistry.DuplicateMemberException();
        }
        if (response.results().isEmpty()) {
            return Optional.empty();
        }
        String id = response.results().getFirst().id();
        if (id == null || id.isBlank()) {
            throw new org.springframework.web.client.RestClientException("Missing Members page ID");
        }
        return Optional.of(id);
    }

    public record MemberQuery(List<MemberPage> results, boolean has_more) { }
    public record MemberPage(String id) { }

    private static List<Map<String, Object>> richText(String content) {
        return List.of(Map.of("type", "text", "text", Map.of("content", content)));
    }
}
