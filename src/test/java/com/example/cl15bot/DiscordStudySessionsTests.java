package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

class DiscordStudySessionsTests {
    private static final String ID = "414964289770881035";
    private final MutableClock clock = new MutableClock();
    private final StudyRecordingService recording = mock(StudyRecordingService.class);
    private final List<String> messages = new ArrayList<>();
    private DiscordStudySessions sessions;
    private final DiscordStudySessions.Occupant user = new DiscordStudySessions.Occupant(ID, "jmn", "channel-a");

    @BeforeEach void setup() {
        when(recording.processDiscordAutomatic(any())).thenReturn(ResponseEntity.ok(
                new StudyCommandController.CommandResponse("ephemeral", "✅ 저장했습니다.")));
        sessions = new DiscordStudySessions(new DiscordMemberMapping("{\"" + ID + "\":\"정명남\"}"),
                recording, clock, Runnable::run, (id, message) -> messages.add(message));
        clock.set("2026-10-13T10:00:50Z");
        sessions.ready(List.of());
    }

    @Test void preservesStartAcrossMovesAndIgnoresDuplicateDeparture() {
        sessions.update(user, true);
        clock.set("2026-10-13T10:02:00Z");
        sessions.update(new DiscordStudySessions.Occupant(ID, "jmn", "channel-b"), true);
        clock.set("2026-10-13T10:04:10Z");
        sessions.update(user, false);
        sessions.update(user, false);
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording, times(1)).processDiscordAutomatic(entry.capture());
        assertEquals(4, entry.getValue().minutes());
        assertEquals(19, entry.getValue().startTime().getHour());
        assertEquals(0, entry.getValue().startTime().getMinute());
        assertEquals(0, entry.getValue().endTime().getSecond());
        assertEquals("모각코", entry.getValue().subject());
    }

    @Test void rejectsActual179SecondsEvenWhenRoundedIntervalIsThreeMinutes() {
        sessions.update(user, true);
        clock.set("2026-10-13T10:03:49Z");
        sessions.update(user, false);
        verifyNoInteractions(recording);
        assertTrue(messages.isEmpty());
    }

    @Test void acceptsExactlyThreeMinutesAndKeepsCrossMidnightInOneRow() {
        clock.set("2026-10-13T14:59:00Z");
        sessions.update(user, true);
        clock.set("2026-10-13T15:02:00Z");
        sessions.update(user, false);
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording).processDiscordAutomatic(entry.capture());
        assertEquals("2026-10-13", entry.getValue().date().toString());
        assertEquals(14, entry.getValue().endTime().getDayOfMonth());
        assertEquals(3, entry.getValue().minutes());
    }

    @Test void rejectsOver24HoursAndYearBoundary() {
        sessions.update(user, true);
        clock.set("2026-10-14T10:02:00Z");
        sessions.update(user, false);
        clock.set("2026-12-31T14:50:00Z");
        sessions.update(user, true);
        clock.set("2026-12-31T15:10:00Z");
        sessions.update(user, false);
        verifyNoInteractions(recording);
        assertEquals(2, messages.size());
    }

    @Test void unmappedUserIsNotifiedOnlyOnceAcrossReconnects() {
        var unknown = new DiscordStudySessions.Occupant("745646325558935654", "unknown", "voice");
        sessions.update(unknown, true);
        sessions.update(unknown, false);
        sessions.update(unknown, true);
        sessions.disconnected();
        sessions.ready(List.of(unknown));
        assertEquals(1, messages.size());
        verifyNoInteractions(recording);
    }

    @Test void cutsAtLastHeartbeatAndStartsFreshAfterReconnect() {
        sessions.update(user, true);
        clock.set("2026-10-13T10:04:00Z");
        sessions.checkpoint();
        clock.set("2026-10-13T10:10:00Z");
        sessions.disconnected();
        sessions.disconnected();
        sessions.update(user, true); // replay while disconnected must be ignored
        clock.set("2026-10-13T10:20:00Z");
        sessions.ready(List.of(user));
        clock.set("2026-10-13T10:24:00Z");
        sessions.update(user, false);
        var entries = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording, times(2)).processDiscordAutomatic(entries.capture());
        assertEquals(4, entries.getAllValues().get(0).endTime().getMinute());
        assertEquals(20, entries.getAllValues().get(1).startTime().getMinute());
    }

    @Test void overlapReportsMeasuredPeriodAndDoesNotRetry() {
        when(recording.processDiscordAutomatic(any())).thenReturn(ResponseEntity.ok(
                new StudyCommandController.CommandResponse("ephemeral", "⚠️ 겹침 https://notion.so/existing")));
        sessions.update(user, true);
        clock.set("2026-10-13T10:05:00Z");
        sessions.update(user, false);
        assertTrue(messages.getFirst().contains("측정 구간"));
        assertTrue(messages.getFirst().contains("https://notion.so/existing"));
        verify(recording, times(1)).processDiscordAutomatic(any());
    }

    @Test void mappingRejectsTwoAccountsForSameMember() {
        assertThrows(IllegalArgumentException.class, () -> new DiscordMemberMapping(
                "{\"1536351079917297664\":\"박지원\",\"852884468623474688\":\" 박지원 \"}"));
    }

    @Test void restartsBeginAtReadyTimeForExistingOccupants() {
        sessions.disconnected();
        clock.set("2026-10-13T11:00:00Z");
        sessions.ready(List.of(user));
        clock.set("2026-10-13T11:03:00Z");
        sessions.update(user, false);
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording).processDiscordAutomatic(entry.capture());
        assertEquals(20, entry.getValue().startTime().getHour());
        assertEquals(3, entry.getValue().minutes());
    }

    static class MutableClock extends Clock {
        private Instant instant;
        void set(String instant) { this.instant = Instant.parse(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
