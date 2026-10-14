package com.example.cl15bot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
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
import org.springframework.context.SmartLifecycle;

@Component
@EnableConfigurationProperties(DiscordProperties.class)
public class DiscordBot extends ListenerAdapter implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);
    private final DiscordProperties properties;
    private final BotProperties notionProperties;
    private DiscordStudySessions sessions;
    private DiscordStudyCommands commands;
    private final StudyRecordingService recording;
    private final Clock clock;
    private final Executor executor;
    private int loginFailures;
    private final ScheduledExecutorService loginExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "discord-login");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean loginStarted = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final DiscordTasks tasks = new DiscordTasks();
    private volatile boolean stopping;
    private volatile boolean running;
    private volatile JDA jda;

    public DiscordBot(DiscordProperties properties, BotProperties notionProperties,
            StudyRecordingService recording, Clock clock, @Qualifier("studyExecutor") Executor executor) {
        this.properties = properties;
        this.notionProperties = notionProperties;
        this.recording = recording; this.clock = clock; this.executor = executor;
        // Safe inert listeners keep lifecycle/event methods usable before validation.
        initialize(new DiscordMemberMapping("{}"));
    }

    void initialize(DiscordMemberMapping mapping) {
        commands = new DiscordStudyCommands(properties, notionProperties, mapping, recording, clock, executor,
                this::sendDm, tasks, this::scheduleRetry);
        sessions = new DiscordStudySessions(mapping, recording, clock, executor, this::sendDm, tasks);
    }

    @PostConstruct
    public void start() {
        if (stopping) return;
        running = true;
        if (!properties.enabled()) {
            log.info("Discord disabled: DISCORD_BOT_TOKEN is empty");
            return;
        }
        synchronized (lifecycleLock) {
            if (stopping || !loginStarted.compareAndSet(false, true)) return;
            try {
                if (properties.guildId() == null || !properties.guildId().matches("[0-9]{17,20}")
                        || !notionProperties.hasNotionConfiguration()) throw new IllegalArgumentException();
                initialize(new DiscordMemberMapping(properties.memberMappings()));
            } catch (IllegalArgumentException ex) {
                log.error("Discord configuration invalid; Discord stopped, MM remains available. Check guild ID, member mappings and Notion settings");
                return;
            }
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
            if (stopping) return;
            long delay = DiscordRetry.delay(ex, loginFailures++);
            log.warn("Discord login failed; web service remains available. type={} retryMs={}", ex.getClass().getSimpleName(), delay);
            if (delay >= 0) scheduleRetry(this::connect, delay);
        }
    }

    void scheduleRetry(Runnable task, long delayMillis) {
        synchronized (lifecycleLock) {
            if (!stopping) loginExecutor.schedule(() -> { if (!stopping) task.run(); }, delayMillis,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    JDA buildClient() {
        return JDABuilder.createLight(properties.botToken(), GatewayIntent.GUILD_VOICE_STATES)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .enableCache(CacheFlag.VOICE_STATE)
                // Spring owns shutdown so JDA's independent JVM hook cannot close REST early.
                .setEnableShutdownHook(false)
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
        if (stopping) return;
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
        if (stopping) return;
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
        Runnable complete = tasks.begin();
        try {
            client.retrieveUserById(userId).flatMap(user -> user.openPrivateChannel())
                    .flatMap(channel -> {
                        String text = DiscordMessages.bounded(message);
                        var card = DiscordMessages.card(text);
                        return (card == null ? channel.sendMessage(text) : channel.sendMessageEmbeds(card))
                                .setComponents(DiscordMessages.buttons(text))
                                .setAllowedMentions(java.util.List.of());
                    })
                    .queue(ignored -> complete.run(), failure -> {
                        log.warn("Discord DM delivery failed: type={}", failure.getClass().getSimpleName());
                        complete.run();
                    });
        } catch (RuntimeException ex) {
            complete.run();
            log.warn("Discord DM delivery failed: type={}", ex.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void stop() {
        synchronized (lifecycleLock) {
            if (stopping) return;
            stopping = true;
            running = false;
            commands.stopAccepting();
            sessions.stop();
            loginExecutor.shutdownNow();
        }
        // Drain while studyExecutor and Discord REST are still available. Leave headroom
        // in Spring's 20-second shutdown phase; deployments avoid active study periods.
        try {
            if (!tasks.await(java.time.Duration.ofSeconds(15))) {
                log.warn("Discord shutdown drain timed out; pending work/deliveries={}", tasks.pending());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Discord shutdown drain interrupted; pending work/deliveries={}", tasks.pending());
        } finally {
            if (jda != null) jda.shutdown();
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
