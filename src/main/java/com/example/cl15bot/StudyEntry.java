package com.example.cl15bot;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public record StudyEntry(int minutes, String subject, String userId, String userName,
        String channelId, ZonedDateTime endTime) {
    public static final String HELP = "사용법: /study <분(1~1440)> <공부 내용>\n예: /study 60 Java 스트림 복습\n입력 시점을 종료 시간으로 기록합니다.\n내 사용자 ID 확인: /study whoami";

    public ZonedDateTime startTime() {
        return endTime.minusMinutes(minutes);
    }

    public LocalDate date() {
        return startTime().toLocalDate();
    }

    public static StudyEntry parse(String text, String userId, String userName,
            String channelId, ZonedDateTime endTime) {
        if (text == null || text.length() > 2000) {
            throw new IllegalArgumentException("입력은 2000자 이내로 작성해주세요.\n" + HELP);
        }
        String[] parts = text.strip().split("\\s+", 2);
        int minutes;
        try {
            minutes = Integer.parseInt(parts[0]);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(HELP);
        }
        if (minutes < 1 || minutes > 1440 || parts.length != 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException(HELP);
        }
        if (userId == null || userId.isBlank() || userName == null || userName.isBlank()
                || userId.length() > 200 || userName.length() > 200
                || channelId == null || channelId.isBlank() || channelId.length() > 200) {
            throw new IllegalArgumentException("Mattermost 사용자 또는 채널 정보가 없습니다.");
        }
        return new StudyEntry(minutes, parts[1].strip(), userId, userName, channelId,
                endTime.truncatedTo(ChronoUnit.MINUTES));
    }
}
