package com.example.cl15bot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.GatewayPingEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.guild.GuildAvailableEvent;
import net.dv8tion.jda.api.events.guild.GuildUnavailableEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(DiscordProperties.class)
public class DiscordBot extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);
    private final DiscordProperties properties;
    private final BotProperties notionProperties;
    private final DiscordStudySessions sessions;
    private final DiscordStudyCommands commands;
    private final ExecutorService loginExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "discord-login");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean loginStarted = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private boolean stopping;
    private volatile JDA jda;

    public DiscordBot(DiscordProperties properties, BotProperties notionProperties,
            StudyRecordingService recording, Clock clock, @Qualifier("studyExecutor") Executor executor) {
        this.properties = properties;
        this.notionProperties = notionProperties;
        var mapping = new DiscordMemberMapping(properties.memberMappings());
        commands = new DiscordStudyCommands(properties, notionProperties, mapping, recording, clock, executor);
        sessions = new DiscordStudySessions(mapping,
                recording, clock, executor, this::sendDm);
    }

    @PostConstruct
    void start() {
        if (!properties.enabled()) {
            log.info("Discord disabled: DISCORD_BOT_TOKEN is empty");
            return;
        }
        if (properties.guildId() == null || !properties.guildId().matches("[0-9]{17,20}")) {
            throw new IllegalArgumentException("DISCORD_GUILD_ID에 대상 서버 ID를 입력해주세요.");
        }
        if (!notionProperties.hasNotionConfiguration()) {
            throw new IllegalArgumentException("Discord 실행 전에 Notion 토큰과 두 데이터 소스 ID를 설정해주세요.");
        }
        synchronized (lifecycleLock) {
            if (stopping || !loginStarted.compareAndSet(false, true)) return;
            // JDA verifies the token with a blocking REST call inside build(). A long
            // rate-limit wait must not prevent Tomcat from binding Render's PORT.
            loginExecutor.execute(this::connect);
        }
        log.info("Discord login started in background; web startup does not wait for Discord");
    }

    private void connect() {
        try {
            JDA client = buildClient();
            synchronized (lifecycleLock) {
                if (stopping) client.shutdownNow();
                else jda = client;
            }
        } catch (RuntimeException ex) {
            // Do not log exception messages, credentials, or HTTP response bodies.
            log.error("Discord login failed; web service remains available. Check bot token and Discord access. type={}",
                    ex.getClass().getSimpleName());
        }
    }

    JDA buildClient() {
        return JDABuilder.createLight(properties.botToken(), GatewayIntent.GUILD_VOICE_STATES)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .enableCache(CacheFlag.VOICE_STATE)
                .setAutoReconnect(true)
                .addEventListeners(this, commands).build();
    }

    @Override public void onReady(ReadyEvent event) { reconcile(event.getJDA()); }
    @Override public void onSessionResume(SessionResumeEvent event) { reconcile(event.getJDA()); }
    @Override public void onSessionRecreate(SessionRecreateEvent event) { reconcile(event.getJDA()); }
    @Override public void onGuildJoin(GuildJoinEvent event) {
        if (event.getGuild().getId().equals(properties.guildId())) reconcile(event.getJDA());
    }
    @Override public void onGuildAvailable(GuildAvailableEvent event) {
        if (event.getGuild().getId().equals(properties.guildId())) reconcile(event.getJDA());
    }
    @Override public void onGuildUnavailable(GuildUnavailableEvent event) {
        if (event.getGuild().getId().equals(properties.guildId())) sessions.disconnected();
    }
    @Override public void onGuildLeave(GuildLeaveEvent event) {
        if (event.getGuild().getId().equals(properties.guildId())) sessions.disconnected();
    }

    private void reconcile(JDA client) {
        Guild guild = client.getGuildById(properties.guildId());
        if (guild == null) {
            log.error("Discord target guild unavailable; verify guild ID and bot invitation");
            return;
        }
        commands.register(guild);
        var occupants = new ArrayList<DiscordStudySessions.Occupant>();
        for (var channel : guild.getVoiceChannels()) {
            for (var member : channel.getMembers()) {
                if (!member.getUser().isBot()) occupants.add(new DiscordStudySessions.Occupant(
                        member.getId(), member.getUser().getName(), channel.getId()));
            }
        }
        sessions.ready(occupants);
        log.info("Discord study tracking ready; current human voice occupants={}", occupants.size());
    }

    @Override public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (!event.getGuild().getId().equals(properties.guildId()) || event.getMember().getUser().isBot()) return;
        var joined = event.getChannelJoined();
        boolean studying = joined != null && joined.getType() == ChannelType.VOICE;
        sessions.update(new DiscordStudySessions.Occupant(event.getMember().getId(),
                event.getMember().getUser().getName(), studying ? joined.getId() : ""), studying);
    }

    @Override public void onGatewayPing(GatewayPingEvent event) {
        // A heartbeat ACK confirms a live connection without polling individual users.
        sessions.checkpoint();
    }

    @Override public void onStatusChange(StatusChangeEvent event) {
        log.info("Discord connection status: {} -> {}", event.getOldStatus(), event.getNewStatus());
        if (event.getOldStatus() == JDA.Status.CONNECTED && event.getNewStatus() != JDA.Status.CONNECTED) {
            sessions.disconnected();
            log.warn("Discord connection interrupted; sessions closed at last confirmed time");
        }
    }

    private void sendDm(String userId, String message) {
        JDA client = jda;
        if (client == null) {
            log.warn("Discord DM unavailable during startup/shutdown");
            return;
        }
        client.retrieveUserById(userId).flatMap(user -> user.openPrivateChannel())
                .flatMap(channel -> channel.sendMessage(message).setAllowedMentions(java.util.List.of()))
                .queue(ignored -> {}, failure -> log.warn("Discord DM delivery failed: type={}",
                        failure.getClass().getSimpleName()));
    }

    @PreDestroy
    void stop() {
        synchronized (lifecycleLock) {
            stopping = true;
            sessions.disconnected();
            if (jda != null) jda.shutdown();
            loginExecutor.shutdownNow();
        }
    }
}
