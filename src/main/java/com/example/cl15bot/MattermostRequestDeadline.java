package com.example.cl15bot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.web.client.RestClientException;

/** Applies only while processing an asynchronous Mattermost request. */
final class MattermostRequestDeadline {
    static final Duration PROCESS_LIMIT = Duration.ofMinutes(25);
    static final Duration CALLBACK_LIMIT = Duration.ofMinutes(30);
    private static final ThreadLocal<Deadline> current = new ThreadLocal<>();
    private record Deadline(Clock clock, Instant expiresAt) { }

    static <T> T within(Clock clock, Instant receivedAt, Supplier<T> operation) {
        Deadline previous = current.get();
        current.set(new Deadline(clock, receivedAt.plus(PROCESS_LIMIT)));
        try {
            check();
            return operation.get();
        } finally {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    static void check() {
        Deadline deadline = current.get();
        if (deadline != null && !deadline.clock().instant().isBefore(deadline.expiresAt())) {
            throw new ExpiredException();
        }
    }

    static boolean allowsWait(long millis) {
        Deadline deadline = current.get();
        return deadline == null || millis < Math.max(0,
                Duration.between(deadline.clock().instant(), deadline.expiresAt()).toMillis());
    }

    static final class ExpiredException extends RestClientException {
        ExpiredException() {
            super("대기 시간이 길어 저장하지 않았습니다. 같은 명령어로 다시 입력해주세요.");
        }
    }
}
