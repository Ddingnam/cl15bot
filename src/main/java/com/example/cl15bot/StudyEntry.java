package com.example.cl15bot;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

public record StudyEntry(int minutes, String subject, String memberName, String userId, String userName,
        String channelId, ZonedDateTime endTime) {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    public static final String EXAMPLE = "예: /study 2026-10-12 19:00 20:30 정명남 | Java 복습";
    public static final String CROSS_DAY_EXAMPLE = "예: /study 2026-10-12 23:00 2026-10-13 01:00 정명남 | Java 복습";
    public static final String HELP = "같은 날: /study <시작일 YYYY-MM-DD> <시작 HH:mm> <종료 HH:mm> <Members 이름> | <공부 내용>\n"
            + EXAMPLE + "\n다른 날: /study <시작일 YYYY-MM-DD> <시작 HH:mm> <종료일 YYYY-MM-DD> <종료 HH:mm> <Members 이름> | <공부 내용>\n"
            + CROSS_DAY_EXAMPLE + "\n자정을 넘긴 공부는 종료 날짜도 입력해주세요. 공부 시간은 1~1440분까지 가능합니다.";

    public ZonedDateTime startTime() { return endTime.minusMinutes(minutes); }
    public LocalDate date() { return startTime().toLocalDate(); }

    public static StudyEntry parse(String text, String userId, String userName,
            String channelId, ZonedDateTime now) {
        if (text == null || text.length() > 2000) {
            throw invalid("입력은 2000자 이내로 작성해주세요.");
        }
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && !Character.isWhitespace(c))) {
            throw invalid("입력에 제어 문자를 사용할 수 없습니다. 제어 문자를 제거한 뒤 다시 입력해주세요.");
        }
        String[] namedSubject = text.strip().split("\\|", 2);
        if (namedSubject.length != 2) throw invalidFormat("이름과 공부 내용을 '|'로 구분해주세요.");
        if (namedSubject[1].isBlank()) throw invalidFormat("공부 내용을 입력해주세요.");
        String[] parts = namedSubject[0].strip().split("\\s+", 4);
        if (parts.length != 4) throw invalidFormat("날짜·시작·종료·이름을 모두 입력해주세요.");
        boolean explicitEndDate = !parts[2].contains(":");
        if (explicitEndDate) {
            parts = namedSubject[0].strip().split("\\s+", 5);
            if (parts.length != 5) throw invalidFormat("종료 날짜를 적었다면 종료 시각과 Members 이름도 입력해주세요.", CROSS_DAY_EXAMPLE);
        }
        String example = explicitEndDate ? CROSS_DAY_EXAMPLE : EXAMPLE;
        String endDateText = explicitEndDate ? parts[2] : parts[0];
        String endClockText = parts[explicitEndDate ? 3 : 2];
        if (!parts[0].matches("\\d{4}-\\d{2}-\\d{2}")
                || !endDateText.matches("\\d{4}-\\d{2}-\\d{2}")
                || !parts[1].matches("\\d{2}:\\d{2}") || !endClockText.matches("\\d{2}:\\d{2}")) {
            throw invalidFormat("날짜는 YYYY-MM-DD, 시각은 HH:mm 형식으로 입력해주세요.", example);
        }
        ZonedDateTime start;
        ZonedDateTime end;
        try {
            LocalDate date = LocalDate.parse(parts[0], DATE);
            LocalDate endDate = LocalDate.parse(endDateText, DATE);
            if (date.getYear() < 1 || endDate.getYear() < 1) throw invalid("날짜의 연도는 0001 이상이어야 합니다. 실제 공부한 날짜로 수정해주세요.");
            LocalTime startClock = LocalTime.parse(parts[1], TIME);
            LocalTime endClock = LocalTime.parse(endClockText, TIME);
            start = date.atTime(startClock).atZone(KOREA);
            end = endDate.atTime(endClock).atZone(KOREA);
            if (start.equals(end)) throw invalid("시작과 종료 시각이 같을 수 없습니다. 실제 공부한 시작·종료 시각으로 수정해주세요.");
            if (end.isBefore(start)) throw invalid(explicitEndDate
                    ? "종료 일시는 시작 일시보다 늦어야 합니다. 시작·종료 날짜와 시각을 확인해 수정해주세요."
                    : "종료 시각이 시작 시각보다 빠릅니다. 시각을 수정하거나 자정을 넘긴 공부라면 종료 날짜도 입력해주세요.");
        } catch (DateTimeParseException ex) {
            throw invalidFormat("존재하는 날짜와 시각을 입력해주세요.", example);
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (end.isAfter(now)) throw invalid("미래 시각의 공부 기록은 입력할 수 없습니다. 공부를 마친 뒤 입력하거나 날짜·종료 시각을 확인해 수정해주세요.");
        if (minutes < 1 || minutes > 1440) throw invalid("공부 시간은 1~1440분이어야 합니다. 실제 공부한 시작·종료 시각을 확인해 수정해주세요.");
        String memberName = parts[explicitEndDate ? 4 : 3].strip();
        if (memberName.isBlank()) throw invalidFormat("Members 이름을 입력해주세요.", example);
        if (memberName.length() > 200) throw invalid("Members 이름은 200자 이내로 입력해주세요.");
        if (userId == null || userId.isBlank() || userName == null || userName.isBlank()
                || userId.length() > 200 || userName.length() > 200
                || channelId == null || channelId.isBlank() || channelId.length() > 200) {
            throw invalid("Mattermost 사용자 또는 채널 정보를 확인하지 못했습니다. Mattermost에서 다시 실행하고, 계속 실패하면 관리자에게 문의해주세요.");
        }
        return new StudyEntry((int) minutes, namedSubject[1].strip(), memberName, userId, userName, channelId, end);
    }

    public static String inputError(String reason) {
        return reason.startsWith("⚠️ ") ? reason : "⚠️ " + reason;
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(inputError(message));
    }
    private static IllegalArgumentException invalidFormat(String message) {
        return invalidFormat(message, EXAMPLE);
    }
    private static IllegalArgumentException invalidFormat(String message, String example) {
        return new IllegalArgumentException(inputError(message) + "\n" + example);
    }
}
