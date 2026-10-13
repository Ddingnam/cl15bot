package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StudyEntryTests {
    private final ZonedDateTime now = ZonedDateTime.parse("2026-10-15T12:00:00+09:00[Asia/Seoul]");
    private StudyEntry parse(String text) { return StudyEntry.parse(text, "홍 길동", "u1", "login", "channel", now); }

    @Test void shortCommandResolves2026AndMappedName() {
        var entry = parse("1013 1900 2030 Java 복습");
        assertEquals(90, entry.minutes()); assertEquals("홍 길동", entry.memberName());
        assertEquals("Java 복습", entry.subject()); assertEquals("2026-10-13", entry.date().toString());
        assertEquals("2026-10-13T19:00+09:00[Asia/Seoul]", entry.startTime().toString());
    }
    @Test void overnightNeedsExplicitDate() {
        var entry = parse("1013 2300 1014 0100 Java 복습");
        assertEquals(120, entry.minutes()); assertEquals("2026-10-13", entry.date().toString());
        assertEquals("2026-10-14T01:00+09:00[Asia/Seoul]", entry.endTime().toString());
    }
    @Test void boundariesAndMonthChange() {
        assertEquals(1, parse("1013 0000 0001 Java").minutes());
        assertEquals(1440, parse("1013 1900 1014 1900 Java").minutes());
        assertEquals(120, parse("0930 2300 1001 0100 Java").minutes());
        assertEquals(960, parse("1013 0900 1014 0100 Java").minutes());
        assertEquals(120, parse("1013 0900 1013 1100 Java").minutes());
    }
    @Test void subjectIsPreservedAndNumericTextIsAllowedWhenUnambiguous() {
        assertEquals("Java\n스트림 | 복습", parse("1013 1900 2030 Java\n스트림 | 복습").subject());
        assertEquals("42 문제풀이", parse("1013 1900 2030 42 문제풀이").subject());
        assertEquals("문제 1234", parse("1013 1900 2030 문제 1234").subject());
    }
    @ParameterizedTest
    @ValueSource(strings={"", "1013", "1013 1900", "1013 1900 2030", "1013 2300 1014 0100",
        "0230 1900 2030 Java", "0229 1900 2030 Java", "0431 1900 2030 Java", "1301 1900 2030 Java",
        "0000 1900 2030 Java", "1013 2400 2030 Java", "1013 1960 2030 Java", "1013 1900 2360 Java",
        "1013 2300 0230 0100 Java", "1013 2300 1014 2400 Java", "1013 900 2030 Java",
        "10-13 1900 2030 Java", "2026-10-13 19:00 20:30 홍길동 | Java", "20261013 1900 2030 Java",
        "1013 2300 1014 0100 1234 문제풀이", "1013 1900 2030 1234 문제풀이"})
    void missingMalformedOrAmbiguousInputHasTwoLineExample(String text) {
        String message=assertThrows(IllegalArgumentException.class,()->parse(text)).getMessage();
        assertTrue(message.startsWith("⚠️ ")); assertEquals(2, message.lines().count());
        assertTrue(message.lines().skip(1).findFirst().orElseThrow().startsWith("예: /st"));
    }
    @ParameterizedTest
    @ValueSource(strings={"1013 1900 1800 Java", "1013 1900 1900 Java", "1013 2300 1013 0100 Java",
        "1013 1900 1014 1901 Java", "1013 1900 1015 1900 Java", "1231 2300 0101 0100 Java",
        "1015 1200 1201 Java", "1015 2300 1016 0100 Java"})
    void invalidPeriodsHaveActionWithoutExample(String text) {
        String message=assertThrows(IllegalArgumentException.class,()->parse(text)).getMessage();
        assertTrue(message.startsWith("⚠️ ")); assertTrue(message.contains("주세요"));
        assertEquals(1, message.lines().count());
    }
    @Test void missingMappingAndMetadataNeverBecomeStudyNames() {
        String message=assertThrows(IllegalArgumentException.class,()->StudyEntry.parse("1013 1900 2030 Java",null,"u1","login","channel",now)).getMessage();
        assertTrue(message.contains("/st 내정보")); assertFalse(message.contains("예:"));
        assertThrows(IllegalArgumentException.class,()->StudyEntry.parse("1013 1900 2030 Java","홍길동","","login","channel",now));
    }
    @Test void sizeAndControlCharacters() {
        assertThrows(IllegalArgumentException.class,()->parse("1013 1900 2030 "+"가".repeat(2000)));
        assertThrows(IllegalArgumentException.class,()->parse("1013 1900 2030 Java\u0000"));
    }
}
