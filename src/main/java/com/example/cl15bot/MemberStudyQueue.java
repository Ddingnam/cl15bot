package com.example.cl15bot;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Serializes work before occupying executor threads, across both platforms. */
final class MemberStudyQueue {
    private static final int MAX_PENDING = 200;
    private static final int MAX_MEMBER_PENDING = 8;
    private static final int MAX_MANUAL_PER_MINUTE = 5;
    private static final long MANUAL_WINDOW_MILLIS = 60_000;
    static final String BUSY = "⏳ 이전 공부 기록을 처리 중입니다.\n\n결과를 기다린 뒤 다시 입력해주세요.\n"
            + "결과가 오지 않으면 Study Log에서 저장 여부를 확인해주세요.\n\n이번 요청은 접수하지 않았습니다.";
    private final Map<String, ArrayDeque<Work>> queues = new HashMap<>();
    private final Map<String, Window> windows = new HashMap<>();
    private int pending;
    private record Window(long start, int count) { }
    private static final class Work {
        final String member;
        Executor executor;
        Runnable task;
        Runnable rejected;
        boolean started;
        boolean finished;
        Work(String member) { this.member = member; }
    }

    synchronized Ticket reserveManual(String member, Clock clock) {
        if (queues.containsKey(member)) throw new AdmissionException(BUSY);
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= MANUAL_WINDOW_MILLIS);
        Window window = windows.get(member);
        if (window != null && window.count() >= MAX_MANUAL_PER_MINUTE) throw new AdmissionException(
                "⚠️ 짧은 시간에 등록 요청이 많습니다. 1분 후 다시 입력해주세요. 이번 요청은 접수하지 않았습니다.");
        if (pending >= MAX_PENDING) throw new RejectedExecutionException();
        windows.put(member, new Window(window == null ? now : window.start(), window == null ? 1 : window.count() + 1));
        Work work = new Work(member);
        queues.computeIfAbsent(member, key -> new ArrayDeque<>()).add(work);
        pending++;
        return new Ticket(work);
    }

    void automatic(String member, Executor executor, Runnable task, Runnable rejected) {
        Work work = new Work(member);
        work.executor = executor; work.task = task; work.rejected = rejected;
        boolean start;
        synchronized (this) {
            var queue = queues.computeIfAbsent(member, key -> new ArrayDeque<>());
            if (pending >= MAX_PENDING || queue.size() >= MAX_MEMBER_PENDING) {
                if (queue.isEmpty()) queues.remove(member);
                throw new RejectedExecutionException();
            }
            queue.add(work); pending++;
            start = queue.getFirst() == work;
            if (start) work.started = true;
        }
        if (start) dispatch(work, false);
    }

    final class Ticket implements AutoCloseable {
        private final Work work;
        private Ticket(Work work) { this.work = work; }
        void submit(Executor executor, Runnable task) {
            synchronized (MemberStudyQueue.this) {
                if (work.finished || work.started) throw new IllegalStateException("Ticket no longer available");
                work.executor = executor; work.task = task; work.started = true;
            }
            dispatch(work, true);
        }
        @Override public void close() {
            synchronized (MemberStudyQueue.this) { if (work.started || work.finished) return; }
            done(work);
        }
    }

    private void dispatch(Work work, boolean propagate) {
        try { work.executor.execute(() -> { try { work.task.run(); } finally { done(work); } }); }
        catch (RejectedExecutionException ex) {
            try { if (work.rejected != null) work.rejected.run(); }
            finally { done(work); }
            if (propagate) throw ex;
        }
    }

    private void done(Work work) {
        Work next = null;
        synchronized (this) {
            if (work.finished) return;
            work.finished = true; pending--;
            var queue = queues.get(work.member);
            queue.remove(work);
            if (queue.isEmpty()) queues.remove(work.member);
            else if (!queue.getFirst().started && queue.getFirst().task != null) {
                next = queue.getFirst(); next.started = true;
            }
        }
        if (next != null) dispatch(next, false);
    }

    static final class AdmissionException extends RuntimeException {
        AdmissionException(String message) { super(message); }
    }
}
