package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.ZonedDateTime;
import org.junit.jupiter.params.ParameterizedTest;
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
}