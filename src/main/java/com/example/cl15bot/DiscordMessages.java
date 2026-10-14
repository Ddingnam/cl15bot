package com.example.cl15bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import java.util.List;
import java.util.regex.Pattern;

final class DiscordMessages {
    private static final int LIMIT = 2000;
    private static final String OMITTED = "…(일부 생략)";
    private static final Pattern RECORD_LINK = Pattern.compile("(?m)^🔗 \\[([^\\]]+)\\]\\(<(https://[^>]+)>\\)\\s*$");

    static List<ActionRow> buttons(String text) {
        if (!text.startsWith("✅") && !text.startsWith("⏳")) return List.of();
        var match = RECORD_LINK.matcher(text);
        if (!match.find()) return List.of();
        String url = NotionStudyClient.pageLink(null, match.group(2));
        if (url.isEmpty()) return List.of();
        String label = match.group(1).contains("Study Log") ? "Study Log 보기" : "Notion 기록 보기";
        return List.of(ActionRow.of(Button.link(url, label).withEmoji(Emoji.fromUnicode("📒"))));
    }

    static MessageEmbed card(String text) {
        if (text.startsWith("⏳")) {
            String description = RECORD_LINK.matcher(text.substring(text.indexOf('\n') + 1)).replaceAll("").strip();
            return new EmbedBuilder().setColor(0xF2C94C).setTitle("⏳ 기록 저장 중")
                    .setDescription(description)
                    .setFooter("결과는 본인에게만 표시됩니다").build();
        }
        if (!text.startsWith("✅")) return null;
        String[] lines = text.split("\n");
        var card = new EmbedBuilder().setColor(0x49B98A).setTitle("✅ 공부 기록 완료")
                .setDescription(lines[0].substring("✅".length()).strip());
        for (String line : lines) {
            if (line.startsWith("🕒 ")) card.addField("🕒 공부 시간", line.substring("🕒 ".length()), false);
            else if (line.startsWith("📚 공부 내용 : ")) {
                String subject = line.substring("📚 공부 내용 : ".length());
                card.addField("📚 공부 내용", subject.length() <= 1024 ? subject
                        : truncate(subject, 1024 - OMITTED.length()) + OMITTED, false);
            }
        }
        return card.setFooter("한국 시간 기준").build();
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
