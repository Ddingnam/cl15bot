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
    public static final int YEAR = 2026;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm").withResolverStyle(ResolverStyle.STRICT);
    public static final String EXAMPLE = "예: /st 1013 1900 2030 Java 복습";
    public static final String CROSS_DAY_EXAMPLE = "예: /st 1013 2300 1014 0100 Java 복습";
    public static final String HELP = "같은 날: /st <MMDD> <시작 HHmm> <종료 HHmm> <공부 내용>\n" + EXAMPLE
            + "\n다른 날: /st <MMDD> <시작 HHmm> <종료 MMDD> <종료 HHmm> <공부 내용>\n" + CROSS_DAY_EXAMPLE
            + "\n날짜는 2026년·한국 시간 기준입니다. 자정을 넘기면 종료 날짜도 적어주세요. 공부 시간은 1~1440분입니다."
            + "\n이름은 MM 계정에 연결된 Members 이름을 사용합니다. 계정 확인: /st 내정보"
            + "\n공부 내용은 문자로 시작해주세요. 네 자리 숫자로 시작하면 날짜·시간과 혼동될 수 있습니다.";

    public ZonedDateTime startTime() { return endTime.minusMinutes(minutes); }
    public LocalDate date() { return startTime().toLocalDate(); }

    public static StudyEntry parse(String text, String memberName, String userId, String userName,
            String channelId, ZonedDateTime now) {
        if (text == null || text.length() > 2000) throw invalid("입력은 2000자 이내로 작성해주세요.");
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && !Character.isWhitespace(c))) {
            throw invalid("입력에 제어 문자를 사용할 수 없습니다. 제어 문자를 제거한 뒤 다시 입력해주세요.");
        }
        String[] parts = text.strip().split("\\s+", 5);
        if (parts.length < 4) throw format("날짜·시작·종료·공부 내용을 모두 입력해주세요.", EXAMPLE);
        boolean explicit = parts[3].matches("[0-9]{4}");
        String example = explicit ? CROSS_DAY_EXAMPLE : EXAMPLE;
        if (explicit && parts.length < 5) throw format("종료 날짜를 적었다면 종료 시각과 공부 내용도 입력해주세요.", example);
        String endDateText = explicit ? parts[2] : parts[0];
        String endClockText = explicit ? parts[3] : parts[2];
        if (!parts[0].matches("[0-9]{4}") || !parts[1].matches("[0-9]{4}")
                || !endDateText.matches("[0-9]{4}") || !endClockText.matches("[0-9]{4}")) {
            throw format("날짜는 MMDD, 시각은 HHmm의 숫자 네 자리로 입력해주세요.", example);
        }
        ZonedDateTime start;
        ZonedDateTime end;
        try {
            start = date(parts[0]).atTime(LocalTime.parse(parts[1], TIME)).atZone(KOREA);
            end = date(endDateText).atTime(LocalTime.parse(endClockText, TIME)).atZone(KOREA);
        } catch (DateTimeParseException ex) {
            throw format("2026년에 존재하는 날짜와 시각을 입력해주세요.", example);
        }
        if (end.equals(start)) throw invalid("시작과 종료 시각이 같을 수 없습니다. 실제 공부한 시작·종료 시각으로 수정해주세요.");
        if (end.isBefore(start)) throw invalid(explicit
                ? "종료 일시는 시작 일시보다 늦어야 합니다. 날짜를 확인해주세요. 2026년을 넘기는 기록은 지원하지 않습니다."
                : "종료 시각이 시작 시각보다 빠릅니다. 시각을 수정하거나 자정을 넘긴 공부라면 종료 날짜도 입력해주세요.");
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes > 1440) throw invalid("공부 시간은 1~1440분이어야 합니다. 실제 공부한 시작·종료 시각을 확인해 수정해주세요.");
        if (end.isAfter(now)) throw invalid("미래 시각의 공부 기록은 입력할 수 없습니다. 공부를 마친 뒤 입력하거나 날짜·종료 시각을 확인해 수정해주세요.");
        String subject = explicit ? parts[4] : text.strip().split("\\s+", 4)[3];
        if (subject.isBlank()) throw format("공부 내용을 입력해주세요.", example);
        if (subject.strip().split("\\s+", 2)[0].matches("[0-9]{4}")) {
            throw format("공부 내용은 네 자리 숫자로 시작할 수 없습니다. '문제 1234'처럼 앞에 설명을 적어주세요.", example);
        }
        if (userId == null || userId.isBlank() || userName == null || userName.isBlank()
                || userId.length() > 200 || userName.length() > 200
                || channelId == null || channelId.isBlank() || channelId.length() > 200) {
            throw invalid("Mattermost 사용자 또는 채널 정보를 확인하지 못했습니다. Mattermost에서 다시 실행하고, 계속 실패하면 관리자에게 문의해주세요.");
        }
        if (memberName == null || memberName.isBlank()) {
            throw invalid("MM 계정에 연결된 Members 이름이 없습니다. /st 내정보로 사용자 ID를 확인하고 관리자에게 계정 연결을 요청해주세요.");
        }
        if (memberName.length() > 200) throw invalid("연결된 Members 이름이 너무 깁니다. 관리자에게 계정 연결 설정 확인을 요청해주세요.");
        return new StudyEntry((int) minutes, subject.strip(), memberName.strip(), userId, userName, channelId, end);
    }

    private static LocalDate date(String text) { return LocalDate.parse(YEAR + text, DATE); }
    public static String inputError(String reason) { return reason.startsWith("⚠️ ") ? reason : "⚠️ " + reason; }
    private static IllegalArgumentException invalid(String text) { return new IllegalArgumentException(inputError(text)); }
    private static IllegalArgumentException format(String text, String example) {
        return new IllegalArgumentException(inputError(text) + "\n" + example);
    }
}
