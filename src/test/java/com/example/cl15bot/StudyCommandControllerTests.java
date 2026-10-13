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
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
    private static final String TEST_CHANNEL_ID = "test-study-channel";
    private static final String MEMBER_PAGE = "00000000-0000-0000-0000-000000000001";
    private final NotionStudyClient notion = mock(NotionStudyClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-11T16:00:00Z"), ZoneId.of("Asia/Seoul"));
    private MockMvc mvc;
    private final MemberRegistry members = mock(MemberRegistry.class);

    @BeforeEach
    void setUp() {
        when(members.pageIdFor("홍길동")).thenReturn(java.util.Optional.of(MEMBER_PAGE));
        mvc = controller(new BotProperties("slash-secret", "notion-secret", "source-id", "스터디", "members-source", TEST_CHANNEL_ID));
    }

    private MockMvc controller(BotProperties properties) {
        return MockMvcBuilders.standaloneSetup(new StudyCommandController(properties, notion, clock,
                members)).build();
    }

    private MockHttpServletRequestBuilder command(String text) {
        return post("/api/mattermost/study").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "slash-secret").param("text", text)
                .param("user_id", "user-123").param("user_name", "mm-login-name").param("channel_id", TEST_CHANNEL_ID);
    }

    @Test
    void storesStudyWithKoreanDateAndRepliesPrivately() throws Exception {
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java 스트림 복습"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response_type").value("ephemeral"))
                .andExpect(jsonPath("$.text").value("✅ 홍길동님의 공부 기록을 저장했습니다.\n🕒 2026-10-12 00:00 ~ 01:00 · 60분\n📚 공부 내용 : Java 스트림 복습"));
        ArgumentCaptor<StudyEntry> saved = ArgumentCaptor.forClass(StudyEntry.class);
        verify(notion).save(saved.capture(), eq(MEMBER_PAGE));
        assertEquals("Java 스트림 복습", saved.getValue().subject());
        assertEquals("홍길동", saved.getValue().memberName());
        verify(members).pageIdFor("홍길동");
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
    @ValueSource(strings = {"", "another-channel", "by1prkd3yty7mybdssneo4uzpx"})
    void rejectsOtherOrMissingChannelsBeforeAnyNotionAccess(String channel) throws Exception {
        mvc.perform(post("/api/mattermost/study").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "slash-secret").param("text", "60 홍길동 | Java")
                .param("user_id", "u1").param("user_name", "사용자").param("channel_id", channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("지정된 공부 기록 채널")));
        verifyNoInteractions(notion, members);
    }

    @Test
    void acceptsConfiguredChannelOverride() throws Exception {
        mvc = controller(new BotProperties("slash-secret", "", "", "스터디", "", "channel-override"));
        mvc.perform(post("/api/mattermost/study").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "slash-secret").param("channel_id", "channel-override").param("text", "help"))
                .andExpect(jsonPath("$.text").value(StudyEntry.HELP));
        verifyNoInteractions(notion, members);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingAllowedChannelConfigurationBlocksAllRequests(String configuredChannel) throws Exception {
        mvc = controller(new BotProperties("slash-secret", "notion-secret", "source-id", "스터디",
                "members-source", configuredChannel));
        mvc.perform(command("help"))
                .andExpect(jsonPath("$.text").value("⚠️ 허용 채널 설정이 완료되지 않아 사용할 수 없습니다. 관리자에게 허용 채널 설정 확인을 요청해주세요."));
        verifyNoInteractions(notion, members);
    }

    @Test
    void storesExplicitTimesAcrossMidnight() throws Exception {
        mvc.perform(command("2026-10-11 23:30 2026-10-12 00:30 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("2026-10-11 23:30 ~ 2026-10-12 00:30")));
        ArgumentCaptor<StudyEntry> saved = ArgumentCaptor.forClass(StudyEntry.class);
        verify(notion).save(saved.capture(), eq(MEMBER_PAGE));
        assertEquals(60, saved.getValue().minutes());
        assertEquals("2026-10-11", saved.getValue().date().toString());
    }

    @Test
    void futureInputDoesNotQueryMembers() throws Exception {
        mvc.perform(command("2026-10-12 01:00 02:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value("⚠️ 미래 시각의 공부 기록은 입력할 수 없습니다. 공부를 마친 뒤 입력하거나 날짜·종료 시각을 확인해 수정해주세요."));
        verifyNoInteractions(notion, members);
    }

    @Test
    void missingOvernightEndDateDoesNotQueryOrSave() throws Exception {
        mvc.perform(command("2026-10-11 23:00 01:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value("⚠️ 종료 시각이 시작 시각보다 빠릅니다. 시각을 수정하거나 자정을 넘긴 공부라면 종료 날짜도 입력해주세요."));
        verifyNoInteractions(notion, members);
    }

    @Test
    void reportsDuplicateWithoutSuccessReply() throws Exception {
        doThrow(new NotionStudyClient.OverlappingStudyException()).when(notion).save(any(), any());
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value("⚠️ 같은 멤버의 기존 기록과 공부 시간이 겹칩니다. 저장하지 않았습니다. Notion에서 기존 기록을 확인하고 겹치지 않는 시간으로 수정해주세요."));
        verify(notion, times(1)).save(any(), eq(MEMBER_PAGE));
    }

    @Test
    void reportsPreflightFailureAsNoWriteWithoutLeakingDetails() throws Exception {
        doThrow(new NotionStudyClient.StudyPreflightException("overlap-query", new ResourceAccessException("secret-detail")))
                .when(notion).save(any(), any());
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("저장 요청을 보내지 않았습니다")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-detail"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "help", "도움말", "0 Java", "1441 Java", "60", "abc Java", "-1 Java", "60.5 Java", "60 Java 복습", "60 | Java", "60 홍길동 | ", "60 홍길동"})
    void helpAndInvalidInputDoNotWrite(String text) throws Exception {
        mvc.perform(command(text)).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString(StudyEntry.EXAMPLE)));
        verifyNoInteractions(notion);
    }

    @Test
    void requiresConfiguredToken() throws Exception {
        mvc = controller(new BotProperties("", "", "", "스터디", "", TEST_CHANNEL_ID));
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(notion);
    }

    @Test
    void missingNotionConfigurationDoesNotWrite() throws Exception {
        mvc = controller(new BotProperties("slash-secret", "", "", "스터디", "", TEST_CHANNEL_ID));
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("관리자에게 Notion 연결 설정 확인을 요청해주세요")));
        verifyNoInteractions(notion);
    }

    @Test
    void mapsNotionSchemaErrorsWithoutLeakingResponseBody() throws Exception {
        doThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "sensitive-detail"))
                .when(notion).save(any(), any());
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("⚠️ Notion 기록 항목 설정이 맞지 않아 저장하지 못했습니다. 관리자에게 Study Log 설정 확인을 요청해주세요."));
    }

    @Test
    void warnsAboutAmbiguousTimeoutWithoutRetrying() throws Exception {
        doThrow(new ResourceAccessException("timeout")).when(notion).save(any(), any());
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("⚠️ 저장 완료 여부를 확인하지 못했습니다. 재입력 전에 Notion에서 해당 멤버·시간의 기록을 확인해주세요. 기록이 없으면 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
        verify(notion, times(1)).save(any(), eq(MEMBER_PAGE));
    }

    @Test
    void memberLookupTimeoutDoesNotSendSaveAndReportsLookupStage() throws Exception {
        when(members.pageIdFor("홍길동")).thenThrow(new ResourceAccessException("private connection details",
                new java.net.http.HttpConnectTimeoutException("private connection details")));
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("멤버를 조회하지 못했습니다")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("저장 요청을 보내지 않았습니다")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private connection details"))));
        verifyNoInteractions(notion);
    }

    @Test
    void unknownUserCannotWriteToAnotherMembersPage() throws Exception {
        when(members.pageIdFor("홍길동")).thenReturn(java.util.Optional.empty());
        mvc = controller(new BotProperties("slash-secret", "notion-secret", "source-id", "스터디",
                "members-source", TEST_CHANNEL_ID));
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(StudyEntry.inputError("Members에서 해당 이름을 찾지 못했습니다. Notion Members의 이름을 확인한 뒤 같은 이름으로 다시 입력해주세요.")));
        verifyNoInteractions(notion);
    }

    @Test
    void duplicateMembersAreRejectedBeforeWriting() throws Exception {
        when(members.pageIdFor("홍길동")).thenThrow(new MemberRegistry.DuplicateMemberException());
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(StudyEntry.inputError("Members에 같은 이름이 중복 등록되어 있습니다. 관리자에게 확인을 요청해주세요.")));
        verifyNoInteractions(notion);
    }

    @Test
    void memberLookupPermissionFailureIsReportedWithoutWriting() throws Exception {
        when(members.pageIdFor("홍길동"))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("연결 권한")));
        verifyNoInteractions(notion);
    }

    @Test
    void whoamiWorksBeforeNotionIsConfiguredWithoutWriting() throws Exception {
        mvc = controller(new BotProperties("slash-secret", "", "", "스터디", "", TEST_CHANNEL_ID));
        mvc.perform(command("whoami")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("user-123")));
        verifyNoInteractions(notion);
    }

    @Test
    void crossingMidnightUsesStartDateAndExactDuration() throws Exception {
        mvc.perform(command("2026-10-11 23:00 2026-10-12 01:00 홍길동 | Java")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("✅ 홍길동님의 공부 기록을 저장했습니다.\n🕒 2026-10-11 23:00 ~ 2026-10-12 01:00 · 120분\n📚 공부 내용 : Java"));
        ArgumentCaptor<StudyEntry> saved = ArgumentCaptor.forClass(StudyEntry.class);
        verify(notion).save(saved.capture(), eq(MEMBER_PAGE));
        assertEquals("2026-10-11", saved.getValue().date().toString());
        assertEquals(120, java.time.Duration.between(saved.getValue().startTime(), saved.getValue().endTime()).toMinutes());
    }

    @Test
    void omittedDateIsRejectedBeforeLookingUpMembers() throws Exception {
        mvc.perform(command("00:00 01:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value("⚠️ 날짜·시작·종료·이름을 모두 입력해주세요.\n" + StudyEntry.EXAMPLE));
        verifyNoInteractions(notion, members);
    }

    @Test
    void multilineSubjectIsDisplayedOnOneLineButStoredUnmodified() throws Exception {
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java\n스트림 복습"))
                .andExpect(jsonPath("$.text").value("✅ 홍길동님의 공부 기록을 저장했습니다.\n🕒 2026-10-12 00:00 ~ 01:00 · 60분\n📚 공부 내용 : Java 스트림 복습"));
        ArgumentCaptor<StudyEntry> saved = ArgumentCaptor.forClass(StudyEntry.class);
        verify(notion).save(saved.capture(), eq(MEMBER_PAGE));
        assertEquals("Java\n스트림 복습", saved.getValue().subject());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500})
    void preflightHttpFailureGivesActionWithoutAmbiguousSaveWarning(int code) throws Exception {
        doThrow(new NotionStudyClient.StudyPreflightException("overlap-query",
                new HttpClientErrorException(HttpStatus.valueOf(code)))).when(notion).save(any(), any());
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith("⚠️ ")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("해주세요")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("재입력 전에"))))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("예:"))));
        verify(notion, times(1)).save(any(), eq(MEMBER_PAGE));
    }

    @Test
    void createServerErrorRequiresCheckingNotionBeforeRetry() throws Exception {
        doThrow(new org.springframework.web.client.HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))
                .when(notion).save(any(), any());
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("재입력 전에 Notion")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("기록이 없으면 같은 명령어로 다시 시도")));
        verify(notion, times(1)).save(any(), eq(MEMBER_PAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "preflight", "member-response", "member-network", "save-response",
        "save-network", "http", "duplicate-member", "duplicate-study"})
    void operationalErrorsAlwaysWarnWithoutExamples(String scenario) throws Exception {
        switch (scenario) {
            case "schema" -> doThrow(new NotionStudyClient.StudySchemaException("속성 오류"))
                    .when(notion).save(any(), any());
            case "preflight" -> doThrow(new NotionStudyClient.StudyPreflightException("overlap-query", new ResourceAccessException("timeout")))
                    .when(notion).save(any(), any());
            case "member-response" -> when(members.pageIdFor("홍길동"))
                    .thenThrow(new org.springframework.web.client.RestClientException("invalid"));
            case "member-network" -> when(members.pageIdFor("홍길동"))
                    .thenThrow(new ResourceAccessException("timeout"));
            case "save-response" -> doThrow(new org.springframework.web.client.RestClientException("invalid"))
                    .when(notion).save(any(), any());
            case "save-network" -> doThrow(new ResourceAccessException("timeout")).when(notion).save(any(), any());
            case "http" -> doThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN)).when(notion).save(any(), any());
            case "duplicate-member" -> when(members.pageIdFor("홍길동")).thenThrow(new MemberRegistry.DuplicateMemberException());
            default -> doThrow(new NotionStudyClient.OverlappingStudyException()).when(notion).save(any(), any());
        }
        mvc.perform(command("2026-10-12 00:00 01:00 홍길동 | Java"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith("⚠️ ")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("예:"))))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("⚠️ ⚠️"))));
    }
}
