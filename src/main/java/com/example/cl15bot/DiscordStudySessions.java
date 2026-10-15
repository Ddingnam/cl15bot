package com.example.cl15bot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-memory test implementation. A full process restart intentionally loses sessions. */
public class DiscordStudySessions {
    private static final Logger log = LoggerFactory.getLogger(DiscordStudySessions.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    static final Duration RECOVERY_GRACE = Duration.ofMinutes(2);
    private final DiscordMemberMapping mapping;
    private final StudyRecordingService recording;
    private final Clock clock;
    private final Executor executor;
    private final BiConsumer<String, String> dm;
    private final MemberStudyQueue taskQueue;
    private final DiscordTasks tasks;
    private final Map<String, Session> active = new HashMap<>();
    private final Set<String> notified = new HashSet<>();
    private boolean connected;
    private boolean stopping;
    private Instant lastConfirmed;
    private Instant disconnectedAt;
    private long recoveryGeneration;
    private Runnable cancelRecovery = () -> {};
    private final BiFunction<Runnable, Long, Runnable> recoveryScheduler;
    private final List<VoiceUpdate> pendingUpdates = new ArrayList<>();

    record VoiceUpdate(Occupant occupant, boolean studying, Instant receivedAt) { }

    record Session(String userId, String username, String channelId, String name, Instant start) { }
    public record Occupant(String userId, String username, String channelId) { }

    public DiscordStudySessions(DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock,
            Executor executor, BiConsumer<String, String> dm) {
        this(mapping, recording, clock, executor, dm, new DiscordTasks());
    }

    DiscordStudySessions(DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock,
            Executor executor, BiConsumer<String, String> dm, DiscordTasks tasks) {
        this(mapping, recording, clock, executor, dm, tasks, (task, delay) -> () -> {});
    }

    DiscordStudySessions(DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock,
            Executor executor, BiConsumer<String, String> dm, DiscordTasks tasks,
            BiFunction<Runnable, Long, Runnable> recoveryScheduler) {
        this.mapping = mapping;
        this.recording = recording;
        this.clock = clock;
        this.executor = executor;
        this.dm = dm;
        this.tasks = tasks;
        this.recoveryScheduler = recoveryScheduler;
        this.taskQueue = StudyRecordingService.taskQueue(recording);
    }

    public synchronized void ready(Iterable<Occupant> occupants) {
        ready(occupants, false);
    }

    public synchronized void ready(Iterable<Occupant> occupants, boolean resumed) {
        if (connected || stopping) return;
        expireIfOverdue();
        Instant now = clock.instant();
        int retained = 0;
        if (disconnectedAt != null) {
            cancelRecovery.run();
            // Only RESUME guarantees replay of missed events. A recreated session
            // is reconciled from its complete voice cache instead.
            if (resumed) {
                for (VoiceUpdate update : pendingUpdates) apply(update);
            }
            Map<String, Occupant> current = new HashMap<>();
            for (Occupant occupant : occupants) current.put(occupant.userId(), occupant);
            for (Session session : List.copyOf(active.values())) {
                Occupant occupant = current.get(session.userId());
                if (occupant == null || (!resumed && !session.channelId().equals(occupant.channelId()))) {
                    active.remove(session.userId());
                    finish(session, lastConfirmed, true);
                } else retained++;
            }
        }
        recoveryGeneration++;
        disconnectedAt = null;
        pendingUpdates.clear();
        connected = true;
        lastConfirmed = now;
        for (Occupant occupant : occupants) enter(occupant, now);
        log.info("Discord study recovery: mode={} retained={} active={}", resumed ? "resume" : "snapshot", retained, active.size());
    }

    public synchronized void checkpoint() {
        if (connected) lastConfirmed = clock.instant();
    }

    public synchronized void update(Occupant occupant, boolean inStudyChannel) {
        if (stopping) return;
        expireIfOverdue();
        var update = new VoiceUpdate(occupant, inStudyChannel, clock.instant());
        if (!connected) {
            if (disconnectedAt != null) pendingUpdates.add(update);
            return;
        }
        lastConfirmed = update.receivedAt();
        apply(update);
    }

    private void apply(VoiceUpdate update) {
        Occupant occupant = update.occupant();
        if (update.studying()) enter(occupant, update.receivedAt());
        else {
            Session session = active.remove(occupant.userId());
            if (session != null) finish(session, update.receivedAt());
        }
    }

    private void enter(Occupant occupant, Instant start) {
        String name = mapping.nameFor(occupant.userId()).orElse(null);
        if (name == null) {
            if (notified.add(occupant.userId())) submit(occupant.userId(), () -> dm.accept(occupant.userId(),
                    "⚠️ 공부 기록 계정이 연결되어 있지 않아 자동 기록되지 않습니다. 관리자에게 계정 등록을 요청해주세요."));
            return;
        }
        Session previous = active.get(occupant.userId());
        active.put(occupant.userId(), new Session(occupant.userId(), occupant.username(),
                occupant.channelId(), name, previous == null ? start : previous.start()));
    }

    public synchronized void disconnected() {
        if (!connected || stopping) return;
        connected = false;
        disconnectedAt = clock.instant();
        long generation = ++recoveryGeneration;
        cancelRecovery = recoveryScheduler.apply(() -> expireRecovery(generation), RECOVERY_GRACE.toMillis());
        log.warn("Discord study tracking suspended; active={} graceMs={}", active.size(), RECOVERY_GRACE.toMillis());
    }

    private synchronized void expireRecovery(long generation) {
        if (stopping || connected || disconnectedAt == null || generation != recoveryGeneration) return;
        expireIfOverdue();
    }

    private void expireIfOverdue() {
        if (disconnectedAt == null || clock.instant().isBefore(disconnectedAt.plus(RECOVERY_GRACE))) return;
        log.warn("Discord study recovery timed out; closing sessions={} at last confirmed time", active.size());
        closeAll(true);
    }

    private void closeAll(boolean interrupted) {
        cancelRecovery.run();
        recoveryGeneration++;
        disconnectedAt = null;
        pendingUpdates.clear();
        var ended = List.copyOf(active.values());
        active.clear();
        for (Session session : ended) finish(session, lastConfirmed, interrupted);
    }

    public synchronized void stop() {
        stopping = true;
        connected = false;
        closeAll(false);
    }

    private void finish(Session session, Instant end) {
        finish(session, end, false);
    }

    private void finish(Session session, Instant end, boolean interrupted) {
        if (Duration.between(session.start(), end).compareTo(Duration.ofMinutes(3)) < 0) return;
        var startTime = session.start().atZone(KOREA).truncatedTo(ChronoUnit.MINUTES);
        var endTime = end.atZone(KOREA).truncatedTo(ChronoUnit.MINUTES);
        long minutes = Duration.between(startTime, endTime).toMinutes();
        String period = NotionStudyClient.period(startTime, endTime);
        submit(session.userId(), () -> {
            if (startTime.getYear() != StudyEntry.YEAR || endTime.getYear() != StudyEntry.YEAR
                    || minutes < 1 || minutes > 1440) {
                dm.accept(session.userId(), "⚠️ 모각코 기록을 저장하지 않았습니다. 2026년 내 24시간 이하 기록만 지원합니다.\n🕒 " + period);
                return;
            }
            StudyEntry entry = new StudyEntry((int) minutes, "모각코", session.name(), session.userId(),
                    session.username(), session.channelId(), endTime, StudyEntry.RegistrationSource.DISCORD_VOICE);
            var response = recording.processDiscordAutomatic(entry).getBody();
            if (response != null) {
                String text = response.text();
                if (interrupted) text += "\n⚠️ 봇 연결 장애로 마지막 정상 확인 시각까지만 기록을 종료했습니다. 이후 공부 시간은 확인 후 수동 등록해주세요.";
                if (text.startsWith("⚠️")) text += "\n🕒 측정 구간: " + period + " · " + minutes + "분\n자동 재저장은 하지 않습니다. Notion 확인 후 수동 처리해주세요.";
                dm.accept(session.userId(), text);
            }
        });
    }

    private void submit(String userId, Runnable task) {
        Runnable complete = tasks.begin();
        Runnable rejected = () -> {
            try {
            log.warn("Discord study queue full; task not accepted");
            dm.accept(userId, "⚠️ 처리 대기열이 가득 차 기록 작업을 접수하지 못했습니다. Notion 확인 후 수동 처리해주세요.");
            } finally { complete.run(); }
        };
        try { taskQueue.automatic(mapping.nameFor(userId).orElse("unregistered:" + userId), executor,
                () -> { try { task.run(); } finally { complete.run(); } }, rejected); }
        catch (RejectedExecutionException ex) { rejected.run(); }
    }
}
