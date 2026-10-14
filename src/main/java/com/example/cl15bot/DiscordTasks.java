package com.example.cl15bot;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks queued work and asynchronous delivery callbacks through normal shutdown. */
final class DiscordTasks {
    private int pending;
    synchronized Runnable begin() {
        pending++;
        AtomicBoolean completed = new AtomicBoolean();
        return () -> {
            if (completed.compareAndSet(false, true)) {
                synchronized (this) { pending--; notifyAll(); }
            }
        };
    }
    Executor executor(Executor delegate) {
        return task -> {
            Runnable complete = begin();
            try {
                delegate.execute(() -> { try { task.run(); } finally { complete.run(); } });
            } catch (RuntimeException ex) { complete.run(); throw ex; }
        };
    }
    synchronized boolean await(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }
    synchronized int pending() { return pending; }
}
