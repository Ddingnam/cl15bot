package com.example.cl15bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

final class DiscordMessages {
    private static final int LIMIT = 2000;
    private static final String OMITTED = "…(일부 생략)";

    static MessageEmbed card(String text) {
        if (text.startsWith("⏳")) {
            return new EmbedBuilder().setColor(0xF2C94C).setTitle("공부 기록 처리 중")
                    .setDescription(text.substring(text.indexOf('\n') + 1).strip())
                    .setFooter("결과는 본인에게만 표시됩니다").build();
        }
        if (!text.startsWith("✅")) return null;
        String[] lines = text.split("\n");
        var card = new EmbedBuilder().setColor(0x57F287).setTitle("공부 기록 완료")
                .setDescription(lines[0].substring("✅".length()).strip());
        for (String line : lines) {
            if (line.startsWith("🕒 ")) card.addField("공부 시간", line.substring("🕒 ".length()), false);
            else if (line.startsWith("📚 공부 내용 : ")) {
                String subject = line.substring("📚 공부 내용 : ".length());
                card.addField("공부 내용", subject.length() <= 1024 ? subject
                        : truncate(subject, 1024 - OMITTED.length()) + OMITTED, false);
            } else if (line.startsWith("🔗 ")) card.addField("기록 확인", line.substring("🔗 ".length()), false);
        }
        return card.setFooter("한국 시간 기준 · 모각봇").build();
    }
    static String bounded(String text) {
        if (text.length() <= LIMIT) return text;
        int subject = text.indexOf("📚 공부 내용 : ");
        int link = text.lastIndexOf("\n🔗 ");
        if (subject >= 0) {
            int start = subject + "📚 공부 내용 : ".length();
            int end = link > start ? link : text.length();
            while (end > start && text.charAt(end - 1) == '\n') end--;
            String prefix = text.substring(0, start), suffix = text.substring(end);
            int room = LIMIT - prefix.length() - suffix.length() - OMITTED.length();
            if (room >= 0) return prefix + truncate(text.substring(start, end), room) + OMITTED + suffix;
        }
        String suffix = link >= 0 ? text.substring(link) : "";
        int room = LIMIT - suffix.length() - OMITTED.length();
        return truncate(text, Math.max(0, room)) + OMITTED + suffix;
    }
    private static String truncate(String text, int length) {
        int end = Math.min(length, text.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
