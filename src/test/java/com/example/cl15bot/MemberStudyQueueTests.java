package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class MemberStudyQueueTests {
    final MemberStudyQueue queue = new MemberStudyQueue();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);
    final List<Runnable> workers = new ArrayList<>();

    @Test void duplicateManualDoesNotOccupyWorkerAndOtherMemberCanProceed() {
        var first = queue.reserveManual("member", clock);
        first.submit(workers::add, () -> {});
        assertThrows(MemberStudyQueue.AdmissionException.class, () -> queue.reserveManual("member", clock));
        queue.reserveManual("other", clock).submit(workers::add, () -> {});
        assertEquals(2, workers.size());
        workers.getFirst().run();
        assertDoesNotThrow(() -> queue.reserveManual("member", clock).close());
    }

    @Test void voiceIntervalsWaitInOrderWithoutOccupyingAdditionalWorkers() {
        var manual = queue.reserveManual("member", clock);
        manual.submit(workers::add, () -> {});
        var saved = new ArrayList<Integer>();
        queue.automatic("member", workers::add, () -> saved.add(1), () -> fail("Rejected voice"));
        queue.automatic("member", workers::add, () -> saved.add(2), () -> fail("Rejected voice"));
        assertEquals(1, workers.size());
        workers.get(0).run();
        assertEquals(2, workers.size());
        workers.get(1).run(); workers.get(2).run();
        assertEquals(List.of(1, 2), saved);
    }

    @Test void failedAcknowledgmentReleasesReservedMemberAndStartsWaitingVoice() {
        var ticket = queue.reserveManual("member", clock);
        queue.automatic("member", workers::add, () -> {}, () -> fail("Rejected"));
        assertTrue(workers.isEmpty());
        ticket.close();
        assertEquals(1, workers.size());
        workers.getFirst().run();
        assertDoesNotThrow(() -> queue.reserveManual("member", clock).close());
    }

    @Test void rejectedExecutorAndFailedTaskReleaseMember() {
        assertThrows(RejectedExecutionException.class, () -> queue.reserveManual("member", clock)
                .submit(task -> { throw new RejectedExecutionException(); }, () -> {}));
        var next = queue.reserveManual("member", clock);
        next.submit(workers::add, () -> { throw new IllegalStateException(); });
        assertThrows(IllegalStateException.class, () -> workers.getFirst().run());
        assertDoesNotThrow(() -> queue.reserveManual("member", clock).close());
    }

    @Test void manualRateWindowExpiresAndVoiceBypassesManualRateLimit() {
        for (int count = 0; count < 5; count++) queue.reserveManual("member", clock).submit(Runnable::run, () -> {});
        assertThrows(MemberStudyQueue.AdmissionException.class, () -> queue.reserveManual("member", clock));
        assertDoesNotThrow(() -> queue.automatic("member", Runnable::run, () -> {}, () -> fail("Rejected")));
        assertDoesNotThrow(() -> queue.reserveManual("member", Clock.offset(clock, Duration.ofMinutes(1))).close());
    }
}
