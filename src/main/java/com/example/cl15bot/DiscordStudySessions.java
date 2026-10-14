package com.example.cl15bot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-memory test implementation. A full process restart intentionally loses sessions. */
public class DiscordStudySessions {
    private static final Logger log = LoggerFactory.getLogger(DiscordStudySessions.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final DiscordMemberMapping mapping;
    private final StudyRecordingService recording;
    private final Clock clock;
    private final Executor executor;
    private final BiConsumer<String, String> dm;
    private final Map<String, Session> active = new HashMap<>();
    private final Set<String> notified = new HashSet<>();
    private boolean connected;
    private boolean stopping;
    private Instant lastConfirmed;

    record Session(String userId, String username, String channelId, String name, Instant start) { }
    public record Occupant(String userId, String username, String channelId) { }

    public DiscordStudySessions(DiscordMemberMapping mapping, StudyRecordingService recording, Clock clock,
            Executor executor, BiConsumer<String, String> dm) {
        this.mapping = mapping;
        this.recording = recording;
        this.clock = clock;
        this.executor = executor;
        this.dm = dm;
    }

    public synchronized void ready(Iterable<Occupant> occupants) {
        if (connected || stopping) return;
        connected = true;
        lastConfirmed = clock.instant();
        // Replayed events during a reconnect are ignored until the current cache is ready.
        for (Occupant occupant : occupants) enter(occupant);
    }

    public synchronized void checkpoint() {
        if (connected) lastConfirmed = clock.instant();
    }

    public synchronized void update(Occupant occupant, boolean inStudyChannel) {
        if (!connected) return;
        lastConfirmed = clock.instant();
        if (inStudyChannel) enter(occupant);
        else {
            Session session = active.remove(occupant.userId());
            if (session != null) finish(session, lastConfirmed);
        }
    }

    private void enter(Occupant occupant) {
        String name = mapping.nameFor(occupant.userId()).orElse(null);
        if (name == null) {
            if (notified.add(occupant.userId())) submit(occupant.userId(), () -> dm.accept(occupant.userId(),
                    "⚠️ 공부 기록 계정이 연결되어 있지 않아 자동 기록되지 않습니다. 관리자에게 계정 등록을 요청해주세요."));
            return;
        }
        active.putIfAbsent(occupant.userId(), new Session(occupant.userId(), occupant.username(),
                occupant.channelId(), name, clock.instant()));
    }

    public synchronized void disconnected() {
        if (!connected) return;
        connected = false;
        var ended = java.util.List.copyOf(active.values());
        active.clear();
        for (Session session : ended) finish(session, lastConfirmed);
    }

    public synchronized void stop() {
        stopping = true;
        disconnected();
    }

    private void finish(Session session, Instant end) {
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
                    session.username(), session.channelId(), endTime);
            var response = recording.process(entry).getBody();
            if (response != null) {
                String text = response.text();
                if (text.startsWith("⚠️")) text += "\n🕒 측정 구간: " + period + " · " + minutes + "분\n자동 재저장은 하지 않습니다. Notion 확인 후 수동 처리해주세요.";
                dm.accept(session.userId(), text);
            }
        });
    }

    private void submit(String userId, Runnable task) {
        try { executor.execute(task); }
        catch (RejectedExecutionException ex) {
            log.warn("Discord study queue full; task not accepted");
            dm.accept(userId, "⚠️ 처리 대기열이 가득 차 기록 작업을 접수하지 못했습니다. Notion 확인 후 수동 처리해주세요.");
        }
    }
}
