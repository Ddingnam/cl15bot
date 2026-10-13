package com.example.cl15bot;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class NotionStudyClient {
    private final BotProperties properties;
    private final RestClient client;

    public NotionStudyClient(BotProperties properties, RestClient notionRestClient) {
        this.properties = properties;
        this.client = notionRestClient;
    }

    public String save(StudyEntry entry, String memberPageId) {
        return save(entry, memberPageId, false);
    }

    public String saveToStudyLog(StudyEntry entry, String memberPageId) {
        return save(entry, memberPageId, true);
    }

    private String save(StudyEntry entry, String memberPageId, boolean linkToStudyLog) {
        Map<String, String> columns;
        ResolvedStudySchema schema;
        try {
            schema = studyColumns();
            columns = schema.columns();
        } catch (RestClientException ex) {
            throw new StudyPreflightException("study-schema", ex);
        }
        if (linkToStudyLog && schema.studyLogUrl().isEmpty()) {
            throw new StudySchemaException("Study Log 전체 로그 주소를 확인하지 못해 저장 요청을 보내지 않았습니다. 관리자에게 데이터베이스 연결 확인을 요청해주세요.");
        }
        rejectOverlap(entry, memberPageId, columns);
        Map<String, Object> fields = Map.of(
                columns.get(properties.notionTitleProperty()), Map.of("title", richText(entry.subject())),
                columns.get("날짜"), Map.of("date", Map.of("start", entry.date().toString())),
                columns.get("이름"), Map.of("relation", List.of(Map.of("id", memberPageId))),
                columns.get("시작 시간"), Map.of("date", Map.of("start", entry.startTime().toOffsetDateTime().toString())),
                columns.get("종료 시간"), Map.of("date", Map.of("start", entry.endTime().toOffsetDateTime().toString())));
        StudyPage saved = client.post().uri("/pages")
                .body(Map.of("parent", Map.of("type", "data_source_id",
                        "data_source_id", properties.notionDataSourceId()), "properties", fields))
                .retrieve().body(StudyPage.class);
        if (saved == null) throw new RestClientException("Missing created Study Log page response");
        String url = pageLink(saved.id(), saved.url());
        if (url.isEmpty()) throw new RestClientException("Missing created Study Log page identity");
        return linkToStudyLog ? schema.studyLogUrl() : url;
    }

    private void rejectOverlap(StudyEntry entry, String memberPageId, Map<String, String> columns) {
        StudyQuery result;
        try {
            result = client.post().uri("/data_sources/{id}/query", properties.notionDataSourceId())
                    .body(Map.of("page_size", 1, "filter", Map.of("and", List.of(
                            Map.of("property", columns.get("이름"), "relation", Map.of("contains", memberPageId)),
                            Map.of("property", columns.get("시작 시간"), "date",
                                    Map.of("before", entry.endTime().toOffsetDateTime().toString())),
                            Map.of("property", columns.get("종료 시간"), "date",
                                    Map.of("after", entry.startTime().toOffsetDateTime().toString()))))))
                    .retrieve().body(StudyQuery.class);
            if (result == null || result.results() == null) {
                throw new RestClientException("Invalid Study Log overlap query response");
            }
        } catch (RestClientException ex) {
            throw new StudyPreflightException("overlap-query", ex);
        }
        if (!result.results().isEmpty() || result.has_more()) {
            throw result.results().isEmpty() ? new OverlappingStudyException()
                    : new OverlappingStudyException(overlapDescription(result.results().getFirst(), columns));
        }
    }

    public static class OverlappingStudyException extends RuntimeException {
        public OverlappingStudyException() {
            super("같은 멤버의 기존 기록과 공부 시간이 겹칩니다. 저장하지 않았습니다. Notion에서 기존 기록을 확인하고 겹치지 않는 시간으로 수정해주세요.");
        }
        public OverlappingStudyException(String details) {
            super("같은 멤버의 기존 기록과 공부 시간이 겹칩니다. 저장하지 않았습니다.\n"
                    + details + "기존 기록을 확인하고 겹치지 않는 시간으로 수정해주세요.");
        }
    }

    public record StudyQuery(List<StudyPage> results, boolean has_more) { }
    public record StudyPage(String id, String url, Map<String, DateProperty> properties) { }
    public record DateProperty(DateValue date) { }
    public record DateValue(String start, String time_zone) { }

    private static String overlapDescription(StudyPage page, Map<String, String> columns) {
        String details = "";
        if (page != null && page.properties() != null) {
            try {
                ZonedDateTime start = propertyTime(page.properties().get(columns.get("시작 시간")));
                ZonedDateTime end = propertyTime(page.properties().get(columns.get("종료 시간")));
                details = "🕒 기존 기록 : " + period(start, end) + "\n";
            } catch (RuntimeException ignored) {
                // A matching row still blocks saving even if its display fields are incomplete.
            }
        }
        String url = page == null ? "" : pageLink(page.id(), page.url());
        return details + (url.isEmpty() ? "" : "🔗 [기존 기록 확인](<" + url + ">)\n");
    }

    private static ZonedDateTime propertyTime(DateProperty property) {
        DateValue date = property.date();
        ZoneId korea = ZoneId.of("Asia/Seoul");
        try {
            return OffsetDateTime.parse(date.start()).atZoneSameInstant(korea);
        } catch (DateTimeParseException ex) {
            return LocalDateTime.parse(date.start()).atZone(date.time_zone() == null
                    ? ZoneId.of("UTC") : ZoneId.of(date.time_zone())).withZoneSameInstant(korea);
        }
    }

    static String period(ZonedDateTime start, ZonedDateTime end) {
        DateTimeFormatter full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return start.format(full) + " ~ " + end.format(start.toLocalDate().equals(end.toLocalDate())
                ? DateTimeFormatter.ofPattern("HH:mm") : full);
    }

    static String pageLink(String id, String url) {
        if (url != null) {
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && host != null
                        && (host.equals("notion.so") || host.endsWith(".notion.so")
                                || host.equals("notion.site") || host.endsWith(".notion.site"))) {
                    return uri.toASCIIString();
                }
            } catch (IllegalArgumentException ignored) { }
        }
        if (id != null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32}")) {
            return "https://www.notion.so/" + id.replace("-", "");
        }
        return "";
    }

    public static class StudyPreflightException extends RuntimeException {
        private final String stage;
        public StudyPreflightException(String stage, RestClientException cause) {
            super("Study Log preflight failed", cause);
            this.stage = stage;
        }
        public String stage() { return stage; }
    }

    private ResolvedStudySchema studyColumns() {
        StudySchema schema = client.get().uri("/data_sources/{id}", properties.notionDataSourceId())
                .retrieve().body(StudySchema.class);
        if (schema == null || schema.properties() == null) {
            throw new StudySchemaException("Study Log 기록 항목을 확인하지 못해 저장 요청을 보내지 않았습니다. 관리자에게 Study Log 설정 확인을 요청해주세요.");
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
                        + "' 기록 항목 설정이 올바르지 않아 저장 요청을 보내지 않았습니다. 관리자에게 해당 항목의 설정 확인을 요청해주세요.");
            }
            resolved.put(column.getKey(), candidates.getFirst().getKey());
        }
        String studyLogUrl = schema.parent() != null && "database_id".equals(schema.parent().type())
                ? pageLink(schema.parent().database_id(), null) : "";
        return new ResolvedStudySchema(resolved, studyLogUrl);
    }

    private record ResolvedStudySchema(Map<String, String> columns, String studyLogUrl) { }
    public record StudySchema(Map<String, ColumnSchema> properties, DatabaseParent parent) { }
    public record DatabaseParent(String type, String database_id) { }
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
