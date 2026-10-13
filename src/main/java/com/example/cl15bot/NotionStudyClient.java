package com.example.cl15bot;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

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
        Map<String, String> columns = studyColumns();
        Map<String, Object> fields = Map.of(
                columns.get(properties.notionTitleProperty()), Map.of("title", richText(entry.subject())),
                columns.get("날짜"), Map.of("date", Map.of("start", entry.date().toString())),
                columns.get("이름"), Map.of("relation", List.of(Map.of("id", memberPageId))),
                columns.get("시작 시간"), Map.of("date", Map.of("start", entry.startTime().toOffsetDateTime().toString())),
                columns.get("종료 시간"), Map.of("date", Map.of("start", entry.endTime().toOffsetDateTime().toString())));
        client.post().uri("/pages")
                .body(Map.of("parent", Map.of("type", "data_source_id",
                        "data_source_id", properties.notionDataSourceId()), "properties", fields))
                .retrieve().toBodilessEntity();
    }

    private Map<String, String> studyColumns() {
        StudySchema schema = client.get().uri("/data_sources/{id}", properties.notionDataSourceId())
                .retrieve().body(StudySchema.class);
        if (schema == null || schema.properties() == null) {
            throw new StudySchemaException("Study Log 속성 정보를 확인하지 못했습니다. 저장 요청은 보내지 않았습니다.");
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> expected = Map.of(properties.notionTitleProperty(), "title",
                "날짜", "date", "이름", "relation", "시작 시간", "date", "종료 시간", "date");
        for (var column : expected.entrySet()) {
            var candidates = schema.properties().entrySet().stream()
                    .filter(actual -> actual.getKey().strip().equals(column.getKey().strip())).toList();
            if (candidates.size() != 1 || candidates.getFirst().getValue() == null
                    || !column.getValue().equals(candidates.getFirst().getValue().type())) {
                throw new StudySchemaException("Study Log의 '" + column.getKey()
                        + "' 속성이 없거나 중복되거나 타입이 다릅니다. 저장 요청은 보내지 않았습니다.");
            }
            resolved.put(column.getKey(), candidates.getFirst().getKey());
        }
        return resolved;
    }

    public record StudySchema(Map<String, ColumnSchema> properties) { }
    public record ColumnSchema(String type) { }
    public static class StudySchemaException extends RuntimeException {
        public StudySchemaException(String message) {
            super(message);
        }
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
