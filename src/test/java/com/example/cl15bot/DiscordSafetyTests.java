package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DiscordSafetyTests {
    private static final Instant RECEIVED = Instant.parse("2026-10-14T01:00:00Z");
    private static final String URL = "https://www.notion.so/0658a93a6c248228802b01b5750d2b9e";
    private final BotProperties properties = new BotProperties("mm", "notion", "study", "title", "members", "", URL);
    private final NotionStudyClient notion = mock(NotionStudyClient.class);
    private final MemberRegistry members = mock(MemberRegistry.class);
    private final StudyRecordingService recording = new StudyRecordingService(properties, notion, members);
    private final StudyEntry entry = StudyEntry.parse("1013 1900 2000 Java", "member", "user", "user", "channel",
            RECEIVED.atZone(ZoneId.of("Asia/Seoul")), StudyEntry.Platform.DISCORD);

    @Test void tenMinuteBoundaryRejectsWithoutEvenQueryingNotion() {
        var response = recording.processDiscord(entry, RECEIVED,
                Clock.fixed(RECEIVED.plusSeconds(600), ZoneOffset.UTC)).getBody();
        assertTrue(response.text().contains("저장하지 않았습니다"));
        verifyNoInteractions(members, notion);
    }

    @Test void alreadyStartedOperationCanFinishAfterTenMinutesAndLinksWholeLog() {
        var clock = new DiscordStudySessionsTests.MutableClock();
        clock.set("2026-10-14T01:09:59Z");
        when(members.pageIdFor("member")).thenAnswer(call -> {
            clock.set("2026-10-14T01:10:30Z");
            return Optional.of("member-page");
        });
        when(notion.saveToStudyLog(entry, "member-page")).thenReturn(URL);
        String text = recording.processDiscord(entry, RECEIVED, clock).getBody().text();
        assertTrue(text.startsWith("✅"));
        assertTrue(text.contains("\n\n🕒"));
        assertTrue(text.contains("Study Log 전체 기록 확인"));
        verify(notion).saveToStudyLog(entry, "member-page");
    }

    @Test void longSuccessPreservesResultTimeAndLinkAndDoesNotSplitEmoji() {
        String prefix = "✅ member님의 공부 기록을 저장했습니다.\n\n🕒 2026-10-13 19:00 ~ 20:00 · 60분\n📝 공부 내용 : ";
        String suffix = "\n\n🔗 [Study Log 전체 기록 확인](<" + URL + ">)";
        String result = DiscordMessages.bounded(prefix + "📚".repeat(1100) + suffix);
        assertTrue(result.length() <= 2000);
        assertTrue(result.startsWith(prefix));
        assertTrue(result.endsWith(suffix));
        assertTrue(result.contains("…(일부 생략)"));
        var card = DiscordMessages.card(result);
        assertEquals("✅ 공부 기록 완료", card.getTitle());
        assertTrue(card.getDescription().length() <= 4096);
        assertTrue(card.getDescription().contains("…(일부 생략)"));
        assertTrue(card.getDescription().contains("\n\n"));
        assertEquals("결과는 본인에게만 표시됩니다", card.getFooter().getText());
        assertEquals(URL, DiscordMessages.buttons(result).getFirst().getButtons().getFirst().getUrl());
        for (int i = 0; i < result.length(); i++) {
            if (Character.isHighSurrogate(result.charAt(i))) assertTrue(Character.isLowSurrogate(result.charAt(++i)));
            else assertFalse(Character.isLowSurrogate(result.charAt(i)));
        }
    }

    @Test void processingCardKeepsRecoveryLinkWhileWarningsStayPlain() {
        var card = DiscordMessages.card("⏳ 공부 기록을 처리 중입니다.\n\n"
                + "확인 전에는 다시 입력하지 마세요.\n\n🔗 [Study Log 전체 기록 확인](<" + URL + ">)");
        assertEquals("⏳ 기록 저장 중", card.getTitle());
        assertEquals(0xF2C94C, card.getColorRaw());
        assertFalse(card.getDescription().contains(URL));
        var button = DiscordMessages.buttons("⏳ 처리 중\n🔗 [Study Log 전체 기록 확인](<" + URL + ">)")
                .getFirst().getButtons().getFirst();
        assertEquals(URL, button.getUrl());
        assertEquals("Study Log 보기", button.getLabel());
        assertNotNull(button.getEmoji());
        assertTrue(card.getDescription().contains("다시 입력하지 마세요"));
        assertNull(DiscordMessages.card("⚠️ 저장하지 않았습니다."));
    }

    @Test void automaticSaveCardShowsConnectionWarningAndKeepsLogButton() {
        String warning = "⚠️ 봇 연결 장애로 마지막 정상 확인 시각까지만 기록을 종료했습니다. 이후 공부 시간은 확인 후 수동 등록해주세요.";
        String text = "✅ member님의 공부 기록을 저장했습니다.\n\n🕒 2026-10-13 19:00 ~ 20:00 · 60분\n"
                + "📝 공부 내용 : 모각코\n\n🔗 [Study Log 전체 기록 확인](<" + URL + ">)\n" + warning;
        assertTrue(DiscordMessages.card(text).getDescription().contains(warning));
        assertEquals(URL, DiscordMessages.buttons(text).getFirst().getButtons().getFirst().getUrl());
    }

    @Test void drainIncludesQueuedTasksAndTheirAsynchronousDelivery() throws Exception {
        var tracker = new DiscordTasks();
        var queue = new ArrayList<Runnable>();
        var delivery = new ArrayList<Runnable>();
        tracker.executor(queue::add).execute(() -> delivery.add(tracker.begin()));
        assertFalse(tracker.await(Duration.ZERO));
        queue.getFirst().run();
        assertFalse(tracker.await(Duration.ZERO));
        delivery.getFirst().run();
        delivery.getFirst().run(); // duplicate completion is harmless
        assertTrue(tracker.await(Duration.ZERO));
        assertEquals(0, tracker.pending());
    }
}
