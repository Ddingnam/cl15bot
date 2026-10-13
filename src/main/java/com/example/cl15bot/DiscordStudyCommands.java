package com.example.cl15bot;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manual input shares MM's parser and recording service; only the transport is Discord-specific. */
public class DiscordStudyCommands extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DiscordStudyCommands.class);
    private static final String PROCESSING = "⏳ 공부 기록을 처리 중입니다. 완료되면 결과를 알려드릴게요.\n결과가 오지 않으면 재입력 전에 Notion에서 저장 여부를 확인해주세요.";
    private final DiscordProperties properties;
    private final BotProperties notionProperties;
    private final DiscordMemberMapping mapping;
    private final StudyRecordingService recording;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean registrationStarted = new AtomicBoolean();

    public DiscordStudyCommands(DiscordProperties properties, BotProperties notionProperties,
            DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock, Executor executor) {
        this.properties = properties;
        this.notionProperties = notionProperties;
        this.mapping = mapping;
        this.recording = recording;
        this.clock = clock;
        this.executor = executor;
    }

    public void register(Guild guild) {
        if (!guild.getId().equals(properties.guildId()) || !registrationStarted.compareAndSet(false, true)) return;
        // Upsert only /st; do not delete other application commands.
        guild.upsertCommand(Commands.slash("st", "2026년 공부 기록 · 본인에게만 결과 표시")
                .addOptions(new OptionData(OptionType.STRING, "입력",
                        "MMDD 시작HHmm [종료MMDD] 종료HHmm 공부내용 / 도움말 / 내정보", false).setMaxLength(2000)))
                .queue(command -> log.info("Discord /st command registered"), failure -> {
                    registrationStarted.set(false);
                    log.warn("Discord /st registration failed: type={}", failure.getClass().getSimpleName());
                });
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("st")) return;
        if (event.getGuild() == null || !event.getGuild().getId().equals(properties.guildId())) {
            reply(event, "⚠️ 이 명령어는 지정된 Discord 서버에서만 사용할 수 있습니다.");
            return;
        }
        var option = event.getOption("입력");
        String text = option == null ? "" : option.getAsString();
        String input = text.strip();
        String userId = event.getUser().getId();
        if (input.isBlank() || input.equals("도움말") || input.equalsIgnoreCase("help")) {
            reply(event, StudyEntry.help(StudyEntry.Platform.DISCORD));
            return;
        }
        if (input.equals("내정보") || input.equalsIgnoreCase("whoami")) {
            reply(event, "Discord 사용자 ID: " + userId + "\n연결된 Members 이름: "
                    + mapping.nameFor(userId).orElse("미등록")
                    + "\n미등록이면 관리자에게 이 사용자 ID와 Members 이름을 전달해주세요.");
            return;
        }
        StudyEntry entry;
        try {
            entry = StudyEntry.parse(text, mapping.nameFor(userId).orElse(null), userId,
                    event.getUser().getName(), event.getChannelId(), ZonedDateTime.now(clock), StudyEntry.Platform.DISCORD);
        } catch (IllegalArgumentException ex) {
            reply(event, ex.getMessage().replace("예: /st ", "예: /st 입력:"));
            return;
        }
        if (!notionProperties.hasNotionConfiguration()) {
            reply(event, "⚠️ Notion 연결 설정이 완료되지 않아 저장할 수 없습니다. 관리자에게 Notion 연결 설정 확인을 요청해주세요.");
            return;
        }
        // ACK before any Notion calls; all edits retain the original ephemeral visibility.
        event.reply(PROCESSING).setEphemeral(true).setAllowedMentions(List.of()).queue(hook -> {
            try {
                executor.execute(() -> {
                    var response = recording.process(entry).getBody();
                    if (response != null) edit(hook, response.text());
                });
            } catch (RejectedExecutionException ex) {
                edit(hook, "⚠️ 현재 처리 중인 요청이 많아 접수하지 못했습니다. 저장 요청을 보내지 않았습니다. 잠시 후 다시 입력해주세요.");
            }
        }, failure -> log.warn("Discord command acknowledgment failed; no save started: type={}",
                failure.getClass().getSimpleName()));
    }

    private static String bounded(String text) {
        return text.length() <= 2000 ? text : text.substring(0, 1990) + "\n(일부 생략)";
    }
    private static void reply(SlashCommandInteractionEvent event, String text) {
        event.reply(bounded(text)).setEphemeral(true).setAllowedMentions(List.of()).queue(ignored -> {},
                failure -> log.warn("Discord private reply failed: type={}", failure.getClass().getSimpleName()));
    }
    private static void edit(InteractionHook hook, String text) {
        hook.editOriginal(bounded(text)).setAllowedMentions(List.of()).queue(ignored -> {},
                failure -> log.warn("Discord command result delivery failed: type={}", failure.getClass().getSimpleName()));
    }
}
