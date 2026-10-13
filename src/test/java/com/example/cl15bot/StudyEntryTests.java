package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StudyEntryTests {
    private final ZonedDateTime now = ZonedDateTime.parse("2026-10-13T12:00:45+09:00[Asia/Seoul]");
    private StudyEntry parse(String text) {
        return StudyEntry.parse(text, "u1", "user", "test-study-channel", now);
    }

    @Test
    void explicitTimesCalculateMinutesAndRetainMultiwordNamesAndSubject() {
        StudyEntry entry = parse("2026-10-12 19:00 20:30 홍 길동 | Java | 스트림");
        assertEquals(90, entry.minutes());
        assertEquals("홍 길동", entry.memberName());
        assertEquals("Java | 스트림", entry.subject());
        assertEquals("2026-10-12T19:00+09:00[Asia/Seoul]", entry.startTime().toString());
    }

    @Test
    void overnightUsesExplicitEndDateAndOriginalStartDate() {
        StudyEntry entry = parse("2026-10-12 23:00 2026-10-13 01:00 홍길동 | 알고리즘");
        assertEquals(120, entry.minutes());
        assertEquals("2026-10-12", entry.date().toString());
        assertEquals("2026-10-13T01:00+09:00[Asia/Seoul]", entry.endTime().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:01", "23:59"})
    void explicitDurationBoundariesUseWholeMinutes(String end) {
        StudyEntry entry = parse("2026-10-12 00:00 " + end + " 홍길동 | Java");
        assertEquals(end.equals("00:01") ? 1 : 1439, entry.minutes());
        assertEquals(0, entry.endTime().getSecond());
    }

    @Test
    void leapDayAndMaximumExplicitPeriodAreValid() {
        assertEquals(1439, parse("2024-02-29 00:00 23:59 홍길동 | Java").minutes());
        assertEquals(1440, parse("2026-10-11 19:00 2026-10-12 19:00 홍 길동 | Java").minutes());
        assertEquals("홍 길동", parse("2026-10-12 19:00 2026-10-12 20:00 홍 길동 | Java").memberName());
        assertEquals(120, parse("2025-12-31 23:00 2026-01-01 01:00 홍길동 | Java").minutes());
    }

    @Test
    void earlierEndWithoutDateRequiresCorrectionRatherThanAutomaticRollover() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> parse("2026-10-12 19:00 18:00 홍길동 | Java")).getMessage();
        assertTrue(message.contains("종료 날짜도 입력해주세요"));
        assertEquals(1, message.lines().count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-10-12 23:00 2026-10-12 01:00 홍길동 | Java",
        "2026-10-12 19:00 2026-10-11 20:00 홍길동 | Java",
        "2026-10-12 19:00 2026-10-12 19:00 홍길동 | Java",
        "2026-10-11 19:00 2026-10-12 19:01 홍길동 | Java",
        "0001-01-01 00:00 2026-10-12 00:00 홍길동 | Java",
        "2026-10-13 11:00 2026-10-13 12:01 홍길동 | Java",
        "2026-10-12 23:00 2026-10-14 01:00 홍길동 | Java"
    })
    void explicitEndDateValueErrorsHaveActionAndNoExample(String text) {
        String message = assertThrows(IllegalArgumentException.class, () -> parse(text)).getMessage();
        assertTrue(message.startsWith("⚠️ "));
        assertTrue(message.contains("해주세요"));
        assertEquals(1, message.lines().count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-10-12 23:00 2026-02-29 01:00 홍길동 | Java",
        "2026-10-12 23:00 2026-10-13 24:00 홍길동 | Java",
        "2026-10-12 23:00 2026/10/13 01:00 홍길동 | Java",
        "2026-10-12 23:00 2026-10-13 01:00 | Java"
    })
    void malformedExplicitEndDateShowsTwoLineWarning(String text) {
        String message = assertThrows(IllegalArgumentException.class, () -> parse(text)).getMessage();
        assertTrue(message.startsWith("⚠️ "));
        assertEquals(2, message.lines().count());
        assertEquals(StudyEntry.CROSS_DAY_EXAMPLE, message.lines().skip(1).findFirst().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-02-29 10:00 11:00 홍길동 | Java", "2026-04-31 10:00 11:00 홍길동 | Java",
        "2026-10-12 24:00 01:00 홍길동 | Java", "2026-10-12 10:60 11:00 홍길동 | Java",
        "2026-10-12 9:00 10:00 홍길동 | Java", "2026-1-12 09:00 10:00 홍길동 | Java",
        "2026-10-12 10:00 10:00 홍길동 | Java", "2026-10-13 11:00 12:01 홍길동 | Java",
        "2026-10-13 23:00 01:00 홍길동 | Java", "2026-10-14 10:00 11:00 홍길동 | Java",
        "2026-10-12 10:00 홍길동 | Java", "2026/10/12 10:00 11:00 홍길동 | Java",
        "0000-01-01 10:00 11:00 홍길동 | Java", "0 홍길동 | Java", "1441 홍길동 | Java",
        "999999999999999 홍길동 | Java", "+60 홍길동 | Java", "60.5 홍길동 | Java",
        "60 | Java", "60 홍길동 | ", "60 홍길동 Java", ""
    })
    void rejectsInvalidInput(String text) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse(text));
        assertTrue(failure.getMessage().startsWith("⚠️ "));
    }

    @Test
    void validatesLengthAndControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> parse("2026-10-12 19:00 20:00 " + "가".repeat(201) + " | Java"));
        assertThrows(IllegalArgumentException.class, () -> parse("2026-10-12 19:00 20:00 홍길동 | " + "가".repeat(2000)));
        assertThrows(IllegalArgumentException.class, () -> parse("2026-10-12 19:00 20:00 홍길동 | Java\u0000"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StudyEntry.parse("2026-10-12 19:00 20:00 홍길동 | Java", "", "user", "c1", now));
        assertEquals("⚠️ Mattermost 사용자 또는 채널 정보를 확인하지 못했습니다. Mattermost에서 다시 실행하고, 계속 실패하면 관리자에게 문의해주세요.", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 홍길동 | Java", "60 홍길동 | Java", "1440 홍길동 | Java"})
    void durationOnlyCommandsAreRejected(String text) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse(text));
        assertEquals("⚠️ 날짜·시작·종료·이름을 모두 입력해주세요.\n" + StudyEntry.EXAMPLE, failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-12 19:00 홍길동 | Java", "2026-10-12 9:00 10:00 홍길동 | Java",
        "2026-02-29 10:00 11:00 홍길동 | Java", "2026-10-12 19:00 20:00 홍길동 | ",
        "2026-10-12 19:00 20:00 홍길동 Java"})
    void missingOrMalformedInputShowsWarningAndExample(String text) {
        String message = assertThrows(IllegalArgumentException.class, () -> parse(text)).getMessage();
        assertTrue(message.startsWith("⚠️ "));
        assertEquals(2, message.lines().count());
        assertEquals(StudyEntry.EXAMPLE, message.lines().skip(1).findFirst().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-13 11:00 12:01 홍길동 | Java", "2026-10-12 10:00 10:00 홍길동 | Java",
        "0000-01-01 10:00 11:00 홍길동 | Java"})
    void valueErrorsShowOnlyWarningAndReason(String text) {
        String message = assertThrows(IllegalArgumentException.class, () -> parse(text)).getMessage();
        assertTrue(message.startsWith("⚠️ "));
        assertEquals(1, message.lines().count());
        assertFalse(message.contains(StudyEntry.EXAMPLE));
    }
}
