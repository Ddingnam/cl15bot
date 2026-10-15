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
    private static final String FOOTER = "결과는 본인에게만 표시됩니다";
    private static final String GAP = "\n\n";
    private static final Pattern RECORD_LINK = Pattern.compile("(?m)^🔗 \\[([^\\]]+)\\]\\(<(https://[^>]+)>\\)\\s*$");

    static MessageEmbed help() {
        return new EmbedBuilder().setColor(0x5865F2).setTitle("📖 공부 기록 사용법")
                .setDescription(spaced(
                        "`/st` 선택 → `입력` 옵션 선택 → 공부 내용 입력",
                        "**• 같은 날**\n`" + StudyEntry.EXAMPLE.replace("예: ", "") + "`\n"
                                + "날짜 · 시작 시각 · 종료 시각 · 공부 내용",
                        "**• 자정을 넘긴 경우**\n`" + StudyEntry.CROSS_DAY_EXAMPLE.replace("예: ", "") + "`\n"
                                + "시작 날짜 · 시작 시각 · 종료 날짜 · 종료 시각 · 공부 내용",
                        "**• 계정 확인**\n`/st 내정보`",
                        "**• 입력 기준**\n날짜: MMDD / 시각: HHmm\n"
                                + StudyEntry.YEAR + "년 · 한국 시간 기준\n"
                                + "공부 시간: 1분~24시간, 미래 시각 입력 불가\n공부 내용은 문자로 시작"))
                .setFooter(FOOTER).build();
    }

    static MessageEmbed account(String userId, String memberName) {
        var blocks = new java.util.ArrayList<String>();
        blocks.add("**🆔 Discord 사용자 ID**\n`" + userId + "`");
        blocks.add("**📚 연결된 Members 이름**\n" + (memberName == null ? "미등록" : memberName));
        if (memberName == null) {
            blocks.add("**📌 계정 연결 안내**\n관리자에게 위 사용자 ID와 연결할 Members 이름을 전달해주세요.");
        }
        return new EmbedBuilder().setColor(0x5865F2).setDescription(spaced(blocks.toArray(String[]::new)))
                .setFooter(FOOTER).build();
    }

    private static String spaced(String... blocks) {
        return String.join(GAP, blocks);
    }

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
            description = description.replace("이미 저장됐을 수 있으니 확인 전에는", "이미 저장됐을 수 있으니\n확인 전에는");
            return new EmbedBuilder().setColor(0xF2C94C).setTitle("⏳ 기록 저장 중")
                    .setDescription(spaced(description.split("\n\n")))
                    .setFooter(FOOTER).build();
        }
        if (!text.startsWith("✅")) return null;
        String[] lines = text.split("\n");
        var blocks = new java.util.ArrayList<String>();
        blocks.add(lines[0].substring("✅".length()).strip());
        for (String line : lines) {
            if (line.startsWith("🕒 ")) blocks.add("**🕒 공부 시간**\n" + line.substring("🕒 ".length()));
            else if (line.startsWith("⚠️")) blocks.add(line);
            else if (line.startsWith("📝 공부 내용 : ")) {
                String subject = line.substring("📝 공부 내용 : ".length());
                blocks.add("**📝 공부 내용**\n" + (subject.length() <= 1024 ? subject
                        : truncate(subject, 1024 - OMITTED.length()) + OMITTED));
            }
        }
        return new EmbedBuilder().setColor(0x49B98A).setTitle("✅ 공부 기록 완료")
                .setDescription(spaced(blocks.toArray(String[]::new)))
                .setFooter(FOOTER).build();
    }
    static String bounded(String text) {
        if (text.length() <= LIMIT) return text;
        int subject = text.indexOf("📝 공부 내용 : ");
        int link = text.lastIndexOf("\n🔗 ");
        if (subject >= 0) {
            int start = subject + "📝 공부 내용 : ".length();
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
