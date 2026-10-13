package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.ZonedDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.jayway.jsonpath.JsonPath;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NotionStudyClientTests {
    private static final String SCHEMA = """
            {"properties":{"스터디":{"type":"title"},"날짜%s":{"type":"date"},
            "이름":{"type":"relation"},"시작 시간":{"type":"date"},"종료 시간%s":{"type":"date"},
            "총 공부 시간":{"type":"formula"}}}
            """;

    private StudyEntry entry() {
        return new StudyEntry(60, "Java 복습", "홍길동", "u1", "홍길동", "c1",
                ZonedDateTime.parse("2026-10-12T18:30:00+09:00[Asia/Seoul]"));
    }

    @ParameterizedTest
    @CsvSource({
        "17:30,18:30,member-id,Java 복습,true",
        "17:30,18:30,member-id,다른 내용,true",
        "17:00,18:00,member-id,Java,true",
        "18:00,19:00,member-id,Java,true",
        "17:00,19:00,member-id,Java,true",
        "17:45,18:15,member-id,Java,true",
        "16:30,17:30,member-id,Java,false",
        "18:30,19:30,member-id,Java,false",
        "16:00,17:00,member-id,Java,false",
        "19:00,20:00,member-id,Java,false",
        "17:30,18:30,other-member,Java,false"
    })
    void overlapFilterBlocksIntersectingIntervalsOnlyForSameMember(
            String existingStart, String existingEnd, String member, String subject, boolean blocked) {
        checkInterval(entry(), "2026-10-12T" + existingStart + "+09:00",
                "2026-10-12T" + existingEnd + "+09:00", member, subject, blocked);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-13T00:30+09:00,2026-10-13T01:30+09:00,true",
        "2026-10-12T22:30+09:00,2026-10-12T23:30+09:00,true",
        "2026-10-13T01:00+09:00,2026-10-13T02:00+09:00,false",
        "2026-10-11T23:00+09:00,2026-10-12T01:00+09:00,false"
    })
    void overlapUsesDatesAcrossMidnight(String existingStart, String existingEnd, boolean blocked) {
        StudyEntry overnight = new StudyEntry(120, "Java", "홍길동", "u1", "user", "c1",
                ZonedDateTime.parse("2026-10-13T01:00+09:00[Asia/Seoul]"));
        checkInterval(overnight, existingStart, existingEnd, "member-id", "다른 내용", blocked);
    }

    // Simulate Notion's documented strict before/after and relation filters against an existing row.
    private void checkInterval(StudyEntry entry, String existingStart, String existingEnd,
            String member, String subject, boolean blocked) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.notion.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotionStudyClient client = new NotionStudyClient(
                new BotProperties("slash", "notion-secret", "source-123", "스터디", "members"), builder.build());
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source-123"))
                .andRespond(withSuccess(SCHEMA.formatted("", ""), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source-123/query"))
                .andExpect(jsonPath("$.filter.and.length()").value(3))
                .andRespond(request -> {
                    var body = JsonPath.parse(((MockClientHttpRequest) request).getBodyAsString());
                    List<Map<String, Object>> filters = body.read("$.filter.and");
                    boolean matches = true;
                    for (var filter : filters) {
                        if (filter.get("property").equals("이름")) {
                            matches &= member.equals(((Map<?, ?>) filter.get("relation")).get("contains"));
                        } else if (filter.get("property").equals("시작 시간")) {
                            matches &= OffsetDateTime.parse(existingStart).isBefore(OffsetDateTime.parse(
                                    (String) ((Map<?, ?>) filter.get("date")).get("before")));
                        } else if (filter.get("property").equals("종료 시간")) {
                            matches &= OffsetDateTime.parse(existingEnd).isAfter(OffsetDateTime.parse(
                                    (String) ((Map<?, ?>) filter.get("date")).get("after")));
                        } else {
                            throw new AssertionError("Unexpected overlap filter: " + filter);
                        }
                    }
                    String response = matches
                            ? "{\"results\":[{\"id\":\"existing\",\"properties\":{\"스터디\":{\"title\":[{\"plain_text\":\"" + subject + "\"}]}}}],\"has_more\":false}"
                            : "{\"results\":[],\"has_more\":false}";
                    return withSuccess(response, MediaType.APPLICATION_JSON).createResponse(request);
                });
        if (blocked) {
            assertThrows(NotionStudyClient.OverlappingStudyException.class, () -> client.save(entry, "member-id"));
        } else {
            server.expect(requestTo("https://api.notion.com/v1/pages"))
                    .andRespond(withSuccess("{\"id\":\"new\"}", MediaType.APPLICATION_JSON));
            client.save(entry, "member-id");
        }
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writesExactExistingColumnNamesIncludingTrailingSpaces(boolean trailingSpaces) {
        String suffix = trailingSpaces ? " " : "";
        BotProperties properties = new BotProperties("slash", "notion-secret", "source-123", "스터디", "members");
        RestClient.Builder builder = BotConfiguration.notionClientBuilder(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotionStudyClient client = new NotionStudyClient(properties, builder.build());
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer notion-secret"))
                .andExpect(header("Notion-Version", "2025-09-03"))
                .andRespond(withSuccess(SCHEMA.formatted(suffix, suffix), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source-123/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.page_size").value(1))
                .andExpect(jsonPath("$.filter.and[0].property").value("이름"))
                .andExpect(jsonPath("$.filter.and[0].relation.contains").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.filter.and[1].property").value("시작 시간"))
                .andExpect(jsonPath("$.filter.and[1].date.before").value("2026-10-12T18:30+09:00"))
                .andExpect(jsonPath("$.filter.and[2].property").value("종료 시간" + suffix))
                .andExpect(jsonPath("$.filter.and[2].date.after").value("2026-10-12T17:30+09:00"))
                .andExpect(jsonPath("$.filter.and.length()").value(3))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer notion-secret"))
                .andExpect(header("Notion-Version", "2025-09-03"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.parent.data_source_id").value("source-123"))
                .andExpect(jsonPath("$.properties['스터디'].title[0].text.content").value("Java 복습"))
                .andExpect(jsonPath("$.properties['날짜" + suffix + "'].date.start").value("2026-10-12"))
                .andExpect(jsonPath("$.properties['이름'].relation[0].id").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.properties['시작 시간'].date.start").value("2026-10-12T17:30+09:00"))
                .andExpect(jsonPath("$.properties['종료 시간" + suffix + "'].date.start").value("2026-10-12T18:30+09:00"))
                .andExpect(jsonPath("$.properties.length()").value(5))
                .andExpect(jsonPath("$.properties['총 공부 시간']").doesNotExist())
                .andExpect(jsonPath("$.properties['시즌 학습 시간 (분)']").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"page-123\"}", MediaType.APPLICATION_JSON));
        client.save(entry(), "00000000-0000-0000-0000-000000000001");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-type", "duplicate"})
    void rejectsInvalidSchemaBeforeCreatingAnyRecord(String scenario) {
        String schema = switch (scenario) {
            case "missing" -> "{\"properties\":{}}";
            case "wrong-type" -> SCHEMA.formatted("", "").replace("\"날짜\":{\"type\":\"date\"}", "\"날짜\":{\"type\":\"number\"}");
            default -> SCHEMA.formatted("", "").replace("\"날짜\":{\"type\":\"date\"}", "\"날짜\":{\"type\":\"date\"},\"날짜 \":{\"type\":\"date\"}");
        };
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.notion.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotionStudyClient client = new NotionStudyClient(
                new BotProperties("slash", "notion-secret", "source-123", "스터디", "members"), builder.build());
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source-123"))
                .andRespond(withSuccess(schema, MediaType.APPLICATION_JSON));
        assertThrows(NotionStudyClient.StudySchemaException.class,
                () -> client.save(entry(), "00000000-0000-0000-0000-000000000001"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"overlap", "more-results", "malformed", "timeout", "permission"})
    void overlapOrFailedCheckNeverCreatesPage(String scenario) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.notion.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotionStudyClient client = new NotionStudyClient(
                new BotProperties("slash", "notion-secret", "source-123", "스터디", "members"), builder.build());
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source-123"))
                .andRespond(withSuccess(SCHEMA.formatted(" ", " "), MediaType.APPLICATION_JSON));
        var query = server.expect(requestTo("https://api.notion.com/v1/data_sources/source-123/query"));
        switch (scenario) {
            case "overlap" -> query.andRespond(withSuccess("{\"results\":[{\"id\":\"existing\"}],\"has_more\":false}", MediaType.APPLICATION_JSON));
            case "more-results" -> query.andRespond(withSuccess("{\"results\":[],\"has_more\":true}", MediaType.APPLICATION_JSON));
            case "malformed" -> query.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            case "timeout" -> query.andRespond(withException(new java.net.SocketTimeoutException()));
            default -> query.andRespond(withForbiddenRequest());
        }
        Class<? extends RuntimeException> expected = scenario.equals("overlap") || scenario.equals("more-results")
                ? NotionStudyClient.OverlappingStudyException.class : NotionStudyClient.StudyPreflightException.class;
        assertThrows(expected, () -> client.save(entry(), "member-id"));
        server.verify();
    }
}
