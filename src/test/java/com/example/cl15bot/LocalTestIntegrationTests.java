package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "bot.mattermost-token=local-test-token",
        "bot.notion-token=fake-test-secret",
        "bot.notion-data-source-id=test-source",
        "bot.notion-members-data-source-id=members-source",
        "bot.mattermost-allowed-channel-id=test-study-channel"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalTestIntegrationTests {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    NotionStudyClient notion;

    @BeforeEach
    void registerMember() {
        when(notion.findMemberPageId("홍길동"))
                .thenReturn(java.util.Optional.of("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void servesLocalPageAndReportsConfigurationWithoutSecrets() throws Exception {
        mvc.perform(get("/local-test")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/local-test/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.slashTokenConfigured").value(true))
                .andExpect(jsonPath("$.notionConfigured").value(true))
                .andExpect(jsonPath("$.membersConfigured").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fake-test-secret"))));
        mvc.perform(get("/local-test/status").param("userId", "unknown"))
                .andExpect(jsonPath("$.membersConfigured").value(true));
        verifyNoInteractions(notion);
    }

    @Test
    void localFormUsesRealCommandFlowAndMemberRelation() throws Exception {
        mvc.perform(post("/api/mattermost/study").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "local-test-token").param("text", "2026-10-12 19:00 19:01 홍길동 | 한글 로컬 테스트")
                .param("user_id", "local-test").param("user_name", "테스트").param("channel_id", "test-study-channel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("저장했습니다")));
        ArgumentCaptor<StudyEntry> entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(notion).save(entry.capture(), eq("00000000-0000-0000-0000-000000000001"));
        assertEquals("한글 로컬 테스트", entry.getValue().subject());
    }
}
