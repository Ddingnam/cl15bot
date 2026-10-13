package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class StudyRecordingConcurrencyTests {
    private final BotProperties properties = new BotProperties("mm", "notion", "study", "스터디", "members");
    private final NotionStudyClient notion = mock(NotionStudyClient.class);
    private final MemberRegistry members = mock(MemberRegistry.class);

    private StudyEntry entry(String id) {
        return new StudyEntry(10, "모각코", "정명남", id, id, "voice",
                ZonedDateTime.parse("2026-10-13T20:00:00+09:00[Asia/Seoul]"));
    }

    @Test void serializesSameNotionMemberEvenWithDifferentPlatformIds() throws Exception {
        when(members.pageIdFor("정명남")).thenReturn(Optional.of("page"));
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        var started = new CountDownLatch(12);
        var saveEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.stubbing.Answer<String> save = invocation -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            saveEntered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("save timed out");
                return "https://notion.so/page";
            } finally { inFlight.decrementAndGet(); }
        };
        when(notion.save(any(), eq("page"))).thenAnswer(save);
        when(notion.saveToStudyLog(any(), eq("page"))).thenAnswer(save);
        var recording = new StudyRecordingService(properties, notion, members);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 12; index++) {
                String platformId = "platform-" + index;
                boolean mattermost = index % 2 == 0;
                futures.add(pool.submit(() -> {
                    started.countDown();
                    if (mattermost) recording.processMattermost(entry(platformId));
                    else recording.process(entry(platformId));
                }));
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(saveEntered.await(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, peak.get());
        verify(notion, times(6)).save(any(), eq("page"));
        verify(notion, times(6)).saveToStudyLog(any(), eq("page"));
    }

    @Test void uncertainCreationIsNotRetried() {
        when(members.pageIdFor("정명남")).thenReturn(Optional.of("page"));
        when(notion.save(any(), eq("page"))).thenThrow(new ResourceAccessException("timeout"));
        var response = new StudyRecordingService(properties, notion, members).process(entry("discord")).getBody();
        assertNotNull(response);
        assertTrue(response.text().contains("저장 완료 여부를 확인하지 못했습니다"));
        verify(notion, times(1)).save(any(), eq("page"));
    }
}
