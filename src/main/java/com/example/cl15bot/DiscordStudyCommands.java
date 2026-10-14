package com.example.cl15bot;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
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
    private final DiscordProperties properties;
    private final BotProperties notionProperties;
    private final DiscordMemberMapping mapping;
    private final StudyRecordingService recording;
    private final Clock clock;
    private final Executor executor;
    private final BiConsumer<String, String> dm;
    private final DiscordTasks tasks;
    private final MemberStudyQueue taskQueue;
    private final BiConsumer<Runnable, Long> retryScheduler;
    private int registrationFailures;
    private volatile Guild registrationGuild;
    private volatile boolean stopping;
    private final AtomicBoolean registrationStarted = new AtomicBoolean();

    public DiscordStudyCommands(DiscordProperties properties, BotProperties notionProperties,
            DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock, Executor executor) {
        this(properties, notionProperties, mapping, recording, clock, executor,
                (id, text) -> log.warn("Discord DM fallback unavailable in standalone command listener"), new DiscordTasks());
    }

    DiscordStudyCommands(DiscordProperties properties, BotProperties notionProperties,
            DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock, Executor executor,
            BiConsumer<String, String> dm, DiscordTasks tasks) {
        this(properties, notionProperties, mapping, recording, clock, executor, dm, tasks, (task, delay) -> {});
    }

    DiscordStudyCommands(DiscordProperties properties, BotProperties notionProperties,
            DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock, Executor executor,
            BiConsumer<String, String> dm, DiscordTasks tasks, BiConsumer<Runnable, Long> retryScheduler) {
        this.properties = properties;
        this.notionProperties = notionProperties;
        this.mapping = mapping;
        this.recording = recording;
        this.clock = clock;
        this.executor = tasks.executor(executor);
        this.dm = dm;
        this.tasks = tasks;
        this.taskQueue = StudyRecordingService.taskQueue(recording);
        this.retryScheduler = retryScheduler;
    }

    public void register(Guild guild) {
        if (stopping || !guild.getId().equals(properties.guildId())) return;
        registrationGuild = guild;
        if (!registrationStarted.compareAndSet(false, true)) return;
        try {
        // Upsert only /st; do not delete other application commands.
        guild.upsertCommand(Commands.slash("st", "2026년 공부 기록 · 본인에게만 결과 표시")
                .addOptions(new OptionData(OptionType.STRING, "입력",
                        "MMDD 시작HHmm [종료MMDD] 종료HHmm 공부내용 / 도움말 / 내정보", false).setMaxLength(2000)))
                .queue(command -> log.info("Discord /st command registered"), failure -> registrationFailed(guild, failure));
        } catch (RuntimeException ex) { registrationFailed(guild, ex); }
    }

    private void registrationFailed(Guild guild, Throwable failure) {
        if (stopping) return;
        long delay = DiscordRetry.delay(failure, registrationFailures++);
        log.warn("Discord /st registration failed: type={} retryMs={}", failure.getClass().getSimpleName(), delay);
        if (delay < 0) return;
        retryScheduler.accept(() -> {
            if (stopping) return;
            registrationStarted.set(false);
            register(registrationGuild);
        }, delay);
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("st")) return;
        if (stopping) {
            reply(event, "⚠️ 서버가 재시작 중이라 접수하지 못했습니다. 저장하지 않았습니다. 잠시 후 다시 입력해주세요.");
            return;
        }
        var receivedAt = clock.instant();
        log.info("Discord /st received; targetGuild={} acknowledged={}",
                event.getGuild() != null && event.getGuild().getId().equals(properties.guildId()), event.isAcknowledged());
        if (event.getGuild() == null || !event.getGuild().getId().equals(properties.guildId())) {
            reply(event, "⚠️ 이 명령어는 지정된 Discord 서버에서만 사용할 수 있습니다.");
            return;
        }
        var option = event.getOption("입력");
        String text = option == null ? "" : option.getAsString();
        String input = text.strip();
        String userId = event.getUser().getId();
        if (input.isBlank() || input.equals("도움말") || input.equalsIgnoreCase("help")) {
            event.replyEmbeds(DiscordMessages.help()).setEphemeral(true).setAllowedMentions(List.of())
                    .queue(ignored -> {}, failure -> log.warn("Discord help reply failed: type={}",
                            failure.getClass().getSimpleName()));
            return;
        }
        if (input.equals("내정보") || input.equalsIgnoreCase("whoami")) {
            event.replyEmbeds(DiscordMessages.account(userId, mapping.nameFor(userId).orElse(null)))
                    .setEphemeral(true).setAllowedMentions(List.of()).queue(ignored -> {},
                            failure -> log.warn("Discord account reply failed: type={}", failure.getClass().getSimpleName()));
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
        MemberStudyQueue.Ticket ticket;
        try { ticket = taskQueue.reserveManual(entry.memberName(), clock); }
        catch (MemberStudyQueue.AdmissionException ex) { reply(event, ex.getMessage()); return; }
        catch (RejectedExecutionException ex) { reply(event, "⚠️ 요청이 많아 접수하지 못했습니다. 저장하지 않았습니다."); return; }
        Runnable acknowledged = tasks.begin();
        try {
            String processing = processing();
            event.replyEmbeds(DiscordMessages.card(processing)).setComponents(DiscordMessages.buttons(processing))
                    .setEphemeral(true).setAllowedMentions(List.of()).queue(hook -> {
                try {
                    if (stopping) throw new RejectedExecutionException();
                    ticket.submit(executor, () -> {
                        var response = recording.processDiscord(entry, receivedAt, clock).getBody();
                        if (response != null) edit(hook, userId, response.text());
                    });
                } catch (RejectedExecutionException ex) {
                    edit(hook, userId, "⚠️ 현재 요청을 접수하지 못했습니다. 저장 요청을 보내지 않았습니다. 잠시 후 다시 입력해주세요.");
                } finally { ticket.close(); acknowledged.run(); }
            }, failure -> {
                log.warn("Discord command acknowledgment failed; no save started: type={}", failure.getClass().getSimpleName());
                ticket.close();
                acknowledged.run();
            });
        } catch (RuntimeException ex) {
            ticket.close();
            acknowledged.run();
            log.warn("Discord command acknowledgment failed; no save started: type={}", ex.getClass().getSimpleName());
        }
    }

    void stopAccepting() { stopping = true; }

    private String processing() {
        String url = NotionStudyClient.pageLink(null, notionProperties.notionStudyLogUrl());
        return "⏳ 공부 기록을 처리 중입니다.\n\n"
                + "1~2분 후에도 결과가 없으면 아래 Study Log에서\n본인 기록을 확인해주세요.\n\n"
                + "이미 저장됐을 수 있으니 확인 전에는 다시 입력하지 마세요."
                + (url.isEmpty() ? "" : "\n\n🔗 [Study Log 전체 기록 확인](<" + url + ">)");
    }
    private static void reply(SlashCommandInteractionEvent event, String text) {
        event.reply(DiscordMessages.bounded(text)).setEphemeral(true).setAllowedMentions(List.of()).queue(ignored -> {},
                failure -> log.warn("Discord private reply failed: type={}", failure.getClass().getSimpleName()));
    }
    private void edit(InteractionHook hook, String userId, String text) {
        String message = DiscordMessages.bounded(text);
        Runnable complete = tasks.begin();
        try {
            var card = DiscordMessages.card(message);
            var action = card == null ? hook.editOriginal(message).setEmbeds(List.of())
                    : hook.editOriginalEmbeds(card).setContent("");
            action.setComponents(DiscordMessages.buttons(message)).setAllowedMentions(List.of()).queue(ignored -> complete.run(), failure -> {
                try { fallback(userId, message, failure); } finally { complete.run(); }
            });
        } catch (RuntimeException ex) {
            try { fallback(userId, message, ex); } finally { complete.run(); }
        }
    }

    private void fallback(String userId, String message, Throwable failure) {
        log.warn("Discord command result delivery failed; trying DM without repeating save: type={}",
                failure.getClass().getSimpleName());
        dm.accept(userId, message);
    }
}
