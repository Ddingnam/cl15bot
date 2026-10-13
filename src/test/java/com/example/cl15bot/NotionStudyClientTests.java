package com.example.cl15bot;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NotionStudyClientTests {
    @Test
    void sendsCorrectAuthenticationParentAndTypedProperties() {
        BotProperties properties = new BotProperties("slash", "notion-secret", "source-123", "스터디", "");
        RestClient.Builder builder = BotConfiguration.notionClientBuilder(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotionStudyClient client = new NotionStudyClient(properties, builder.build());
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer notion-secret"))
                .andExpect(header("Notion-Version", "2025-09-03"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.parent.data_source_id").value("source-123"))
                .andExpect(jsonPath("$.properties['스터디'].title[0].text.content").value("Java 복습"))
                .andExpect(jsonPath("$.properties['날짜'].date.start").value("2026-10-12"))
                .andExpect(jsonPath("$.properties['이름'].relation[0].id").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.properties['시작 시간'].date.start").value("2026-10-12T17:30+09:00"))
                .andExpect(jsonPath("$.properties['종료 시간'].date.start").value("2026-10-12T18:30+09:00"))
                .andExpect(jsonPath("$.properties.length()").value(5))
                .andExpect(jsonPath("$.properties['총 공부 시간']").doesNotExist())
                .andExpect(jsonPath("$.properties['시즌 학습 시간 (분)']").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"page-123\"}", MediaType.APPLICATION_JSON));
        client.save(new StudyEntry(60, "Java 복습", "홍길동", "u1", "홍길동", "c1",
                ZonedDateTime.parse("2026-10-12T18:30:00+09:00[Asia/Seoul]")),
                "00000000-0000-0000-0000-000000000001");
        server.verify();
    }
}
