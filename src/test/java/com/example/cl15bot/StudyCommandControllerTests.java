package com.example.cl15bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;

class StudyCommandControllerTests {
    private static final String MEMBER_PAGE = "00000000-0000-0000-0000-000000000001";
    private final NotionStudyClient notion = mock(NotionStudyClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-11T16:00:00Z"), ZoneId.of("Asia/Seoul"));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = controller(new BotProperties("slash-secret", "notion-secret", "source-id", "스터디", "user-123=" + MEMBER_PAGE));
    }

    private MockMvc controller(BotProperties properties) {
        return MockMvcBuilders.standaloneSetup(new StudyCommandController(properties, notion, clock,
                new MemberRegistry(properties))).build();
    }

    private MockHttpServletRequestBuilder command(String text) {
        return post("/api/mattermost/study").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "slash-secret").param("text", text)
                .param("user_id", "user-123").param("user_name", "홍길동").param("channel_id", "channel-123");
    }

    @Test
    void storesStudyWithKoreanDateAndRepliesPrivately() throws Exception {
        mvc.perform(command("60 Java 스트림 복습"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response_type").value("ephemeral"))
                .andExpect(jsonPath("$.text").value("✅ 공부 60분을 Notion에 저장했습니다.\n10-12 00:00 ~ 10-12 01:00 (한국 시간)"));
        ArgumentCaptor<StudyEntry> saved = ArgumentCaptor.forClass(StudyEntry.class);
        verify(notion).save(saved.capture(), eq(MEMBER_PAGE));
        assertEquals("Java 스트림 복습", saved.getValue().subject());
        assertEquals("user-123", saved.getValue().userId());
        assertEquals(60, saved.getValue().minutes());
    }

    @Test
    void rejectsInvalidTokenBeforeWriting() throws Exception {
        mvc.perform(post("/api/mattermost/study").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "bad-token").param("text", "60 Java"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notion);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "help", "도움말", "0 Java", "1441 Java", "60", "abc Java", "-1 Java", "60.5 Java"})
    void helpAndInvalidInputDoNotWrite(String text) throws Exception {
        mvc.perform(command(text)).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("사용법")));
        verifyNoInteractions(notion);
    }

    @Test
    void requiresConfiguredToken() throws Exception {
        mvc = controller(new BotProperties("", "", "", "스터디", ""));
        mvc.perform(command("60 Java")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(notion);
    }

    @Test
    void missingNotionConfigurationDoesNotWrite() throws Exception {
        mvc = controller(new BotProperties("slash-secret", "", "", "스터디", ""));
        mvc.perform(command("60 Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("NOTION_TOKEN")));
        verifyNoInteractions(notion);
    }

    @Test
    void mapsNotionSchemaErrorsWithoutLeakingResponseBody() throws Exception {
        doThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "sensitive-detail"))
                .when(notion).save(any(), any());
        mvc.perform(command("60 Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Notion 속성 이름과 타입을 README의 표에 맞춰주세요."));
    }

    @Test
    void warnsAboutAmbiguousTimeoutWithoutRetrying() throws Exception {
        doThrow(new ResourceAccessException("timeout")).when(notion).save(any(), any());
        mvc.perform(command("60 Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("재입력 전에")));
        verify(notion, times(1)).save(any(), eq(MEMBER_PAGE));
    }

    @Test
    void unknownUserCannotWriteToAnotherMembersPage() throws Exception {
        mvc = controller(new BotProperties("slash-secret", "notion-secret", "source-id", "스터디",
                "other-user=" + MEMBER_PAGE));
        mvc.perform(command("60 Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("등록되지 않은 회원")));
        verifyNoInteractions(notion);
    }

    @Test
    void whoamiWorksBeforeNotionIsConfiguredWithoutWriting() throws Exception {
        mvc = controller(new BotProperties("slash-secret", "", "", "스터디", ""));
        mvc.perform(command("whoami")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("user-123")));
        verifyNoInteractions(notion);
    }

    @Test
    void crossingMidnightUsesStartDateAndExactDuration() throws Exception {
        mvc.perform(command("120 Java")).andExpect(status().isOk());
        ArgumentCaptor<StudyEntry> saved = ArgumentCaptor.forClass(StudyEntry.class);
        verify(notion).save(saved.capture(), eq(MEMBER_PAGE));
        assertEquals("2026-10-11", saved.getValue().date().toString());
        assertEquals(120, java.time.Duration.between(saved.getValue().startTime(), saved.getValue().endTime()).toMinutes());
    }
}
