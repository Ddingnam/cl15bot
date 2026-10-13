package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class NotionPageResponseTests {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String LINK = "https://www.notion.so/00000000000000000000000000000001";
    private static final String SCHEMA = """
            {"properties":{"스터디":{"type":"title"},"날짜 ":{"type":"date"},
            "이름":{"type":"relation"},"시작 시간":{"type":"date"},"종료 시간 ":{"type":"date"}}}
            """;
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.notion.com/v1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final NotionStudyClient client = new NotionStudyClient(
            new BotProperties("slash", "notion", "source", "스터디", "members"), builder.build());
    private final StudyEntry entry = new StudyEntry(60, "Java", "정명남", "u1", "user", "channel",
            ZonedDateTime.parse("2026-10-13T01:00+09:00[Asia/Seoul]"));

    private void schema() {
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source"))
                .andRespond(withSuccess(SCHEMA, MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-12T23:00:00+09:00,2026-10-13T01:00:00+09:00,null",
        "2026-10-12T14:00:00Z,2026-10-12T16:00:00Z,null",
        "2026-10-12T23:00:00,2026-10-13T01:00:00,Asia/Seoul",
        "2026-10-12T23:00:00+09:00,2026-10-13T01:00:00+09:00,Asia/Seoul"
    })
    void overlapShowsKoreanRangeAcrossMidnightAndExistingPageLink(String start, String end, String zone) {
        schema();
        String timezone = zone.equals("null") ? "null" : "\"" + zone + "\"";
        String response = """
                {"results":[{"id":"%s","url":"%s","properties":{
                "스터디":{"type":"title","title":[{"plain_text":"기존 공부"}]},
                "시작 시간":{"type":"date","date":{"start":"%s","time_zone":%s}},
                "종료 시간 ":{"type":"date","date":{"start":"%s","time_zone":%s}}}}],"has_more":false}
                """.formatted(ID, LINK, start, timezone, end, timezone);
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source/query"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var failure = assertThrows(NotionStudyClient.OverlappingStudyException.class, () -> client.save(entry, ID));
        assertTrue(failure.getMessage().contains("🕒 기존 기록 : 2026-10-12 23:00 ~ 2026-10-13 01:00"));
        assertTrue(failure.getMessage().contains("[기존 기록 확인](<" + LINK + ">)"));
        assertFalse(failure.getMessage().contains("예:"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"시작 시간\":{\"date\":{\"start\":\"bad-date\"}}}"})
    void incompleteDisplayFieldsStillBlockSaving(String fields) {
        schema();
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source/query"))
                .andRespond(withSuccess("{\"results\":[{\"id\":\"" + ID + "\",\"properties\":" + fields + "}],\"has_more\":false}", MediaType.APPLICATION_JSON));
        var failure = assertThrows(NotionStudyClient.OverlappingStudyException.class, () -> client.save(entry, ID));
        assertTrue(failure.getMessage().contains("저장하지 않았습니다"));
        assertTrue(failure.getMessage().contains(LINK));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createdPageReturnsCanonicalUrlOrIdFallback(boolean urlPresent) {
        schema();
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source/query"))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));
        String response = urlPresent ? "{\"id\":\"" + ID + "\",\"url\":\"" + LINK + "\"}"
                : "{\"id\":\"" + ID + "\"}";
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertEquals(LINK, client.save(entry, ID));
        server.verify();
    }

    @Test
    void malformedCreateResponseDoesNotReportSuccessOrRetry() {
        schema();
        server.expect(requestTo("https://api.notion.com/v1/data_sources/source/query"))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(RestClientException.class, () -> client.save(entry, ID));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "https://evil.example/notion", "https://notion.so.evil.example/page",
        "https://secret@notion.so/page"})
    void unsafeReturnedLinksUsePageIdInstead(String url) {
        assertEquals(LINK, NotionStudyClient.pageLink(ID, url));
        assertEquals("", NotionStudyClient.pageLink(null, url));
    }
}
