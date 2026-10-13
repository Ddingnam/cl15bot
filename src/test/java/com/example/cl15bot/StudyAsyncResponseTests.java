package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;

class StudyAsyncResponseTests {
    private static final String CALLBACK = "https://mm.example.com/hooks/commands/callback123";
    private static final String LINK = "https://www.notion.so/00000000000000000000000000000001";
    private final NotionStudyClient notion = mock(NotionStudyClient.class);
    private final MemberRegistry members = mock(MemberRegistry.class);
    private final MattermostResponseSender sender = mock(MattermostResponseSender.class);
    private final List<Runnable> queue = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    private MockMvc mvc(Executor executor) {
        return MockMvcBuilders.standaloneSetup(new StudyCommandController(
                new BotProperties("slash", "notion", "source", "스터디", "members"), notion, clock,
                members, sender, executor, new MattermostMemberMapping("{\"u1\":\"정명남\"}"))).build();
    }

    private MockHttpServletRequestBuilder request(String text, String callback) {
        return post("/api/mattermost/study").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "slash").param("text", text).param("response_url", callback)
                .param("user_id", "u1").param("user_name", "user").param("channel_id", "any-channel");
    }

    @Test
    void acknowledgesBeforeNotionWorkAndSendsLinkedSuccessOnce() throws Exception {
        when(members.pageIdFor("정명남")).thenReturn(Optional.of("member-id"));
        when(notion.saveToStudyLog(any(), eq("member-id"))).thenReturn(LINK);
        mvc(queue::add).perform(request("1012 1900 2030 Java 복습", CALLBACK))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response_type").value("ephemeral"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith("⏳")));
        verifyNoInteractions(notion, members, sender);
        assertEquals(1, queue.size());
        queue.getFirst().run();
        var result = ArgumentCaptor.forClass(StudyCommandController.CommandResponse.class);
        verify(sender).send(eq(URI.create(CALLBACK)), result.capture());
        assertEquals("ephemeral", result.getValue().response_type());
        assertEquals("✅ 정명남님의 공부 기록을 저장했습니다.\n🕒 2026-10-12 19:00 ~ 20:30 · 90분\n📚 공부 내용 : Java 복습\n🔗 [Study Log 전체 기록 확인](<" + LINK + ">)", result.getValue().text());
        verify(notion, times(1)).saveToStudyLog(any(), eq("member-id"));
    }

    @Test
    void sendsOverlapRangeAsFinalFailure() throws Exception {
        when(members.pageIdFor("정명남")).thenReturn(Optional.of("member-id"));
        when(notion.saveToStudyLog(any(), any())).thenThrow(new NotionStudyClient.OverlappingStudyException(
                "🕒 기존 기록 : 2026-10-12 19:00 ~ 20:30\n"));
        mvc(queue::add).perform(request("1012 2000 2100 Java", CALLBACK))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith("⏳")));
        queue.getFirst().run();
        var result = ArgumentCaptor.forClass(StudyCommandController.CommandResponse.class);
        verify(sender).send(eq(URI.create(CALLBACK)), result.capture());
        assertTrue(result.getValue().text().startsWith("⚠️"));
        assertTrue(result.getValue().text().contains("기존 기록 : 2026-10-12 19:00 ~ 20:30"));
        assertFalse(result.getValue().text().contains("예:"));
    }

    @Test
    void uncertainSaveIsDeliveredWithoutRecreatingPage() throws Exception {
        when(members.pageIdFor("정명남")).thenReturn(Optional.of("member-id"));
        when(notion.saveToStudyLog(any(), any())).thenThrow(new ResourceAccessException("secret-details"));
        mvc(queue::add).perform(request("1012 1900 2030 Java", CALLBACK));
        queue.getFirst().run();
        var result = ArgumentCaptor.forClass(StudyCommandController.CommandResponse.class);
        verify(sender).send(any(), result.capture());
        assertTrue(result.getValue().text().contains("재입력 전에 Notion"));
        assertFalse(result.getValue().text().contains("secret-details"));
        verify(notion, times(1)).saveToStudyLog(any(), any());
    }

    @Test
    void invalidInputAndHelpRespondImmediatelyWithoutQueueing() throws Exception {
        var mvc = mvc(queue::add);
        mvc.perform(request("1012 1900 1800 Java", CALLBACK))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith("⚠️")));
        mvc.perform(request("help", CALLBACK)).andExpect(jsonPath("$.text").value(StudyEntry.HELP));
        assertTrue(queue.isEmpty());
        verifyNoInteractions(notion, members, sender);
    }

    @Test
    void badCallbackNeverQueuesOrSaves() throws Exception {
        mvc(queue::add).perform(request("1012 1900 2030 Java", "https://mm.example.com/invalid"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("저장하지 않았습니다")));
        assertTrue(queue.isEmpty());
        verifyNoInteractions(notion, members, sender);
    }

    @Test
    void saturatedQueueReturnsActionWithoutNotionRequest() throws Exception {
        mvc(task -> { throw new RejectedExecutionException(); })
                .perform(request("1012 1900 2030 Java", CALLBACK))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("접수하지 못했습니다")));
        verifyNoInteractions(notion, members, sender);
    }

    @Test
    void missingMemberProducesFinalFailureWithoutSave() throws Exception {
        when(members.pageIdFor("정명남")).thenReturn(Optional.empty());
        mvc(queue::add).perform(request("1012 1900 2030 Java", CALLBACK));
        queue.getFirst().run();
        var result = ArgumentCaptor.forClass(StudyCommandController.CommandResponse.class);
        verify(sender).send(any(), result.capture());
        assertTrue(result.getValue().text().contains("연결된 이름을 Members에서 찾지 못했습니다"));
        verifyNoInteractions(notion);
    }
}
