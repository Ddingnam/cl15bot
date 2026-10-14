package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;

class DiscordStartupTests {
    private DiscordBot bot(String token) {
        return spy(new DiscordBot(new DiscordProperties(token, "123456789012345678", "{}"),
                new BotProperties("", "notion", "study", "스터디", "members"),
                mock(StudyRecordingService.class), Clock.systemUTC(), Runnable::run));
    }

    @Test void blockedDiscordLoginDoesNotBlockAppInitializationOrStartTwice() throws Exception {
        var bot = bot("test-token");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        JDA client = mock(JDA.class);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return client;
        }).when(bot).buildClient();
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), bot::start);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            bot.start();
            verify(bot, times(1)).buildClient();
        } finally {
            release.countDown();
            bot.stop();
        }
    }

    @Test void disabledBotNeverAttemptsNetworkLogin() {
        var bot = bot("");
        try {
            bot.start();
            verify(bot, never()).buildClient();
        } finally { bot.stop(); }
    }

    @Test void clientFinishingAfterShutdownIsClosedInsteadOfLeaking() throws Exception {
        var bot = bot("test-token");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        JDA client = mock(JDA.class);
        doAnswer(invocation -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* emulate delayed SDK completion */ }
            }
            return client;
        }).when(bot).buildClient();
        try {
            bot.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            bot.stop();
            release.countDown();
            verify(client, timeout(5000)).shutdownNow();
        } finally {
            release.countDown();
            bot.stop();
        }
    }

    @Test void normalShutdownWaitsForQueuedVoiceSaveBeforeClosingDiscord() throws Exception {
        var queue = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var clock = new DiscordStudySessionsTests.MutableClock();
        clock.set("2026-10-14T01:00:00Z");
        var recording = mock(StudyRecordingService.class);
        when(recording.processDiscordAutomatic(any())).thenReturn(org.springframework.http.ResponseEntity.ok().build());
        var bot = new DiscordBot(new DiscordProperties("", "123456789012345678", "{\"414964289770881035\":\"member\"}"),
                new BotProperties("", "notion", "study", "title", "members"), recording, clock, queue::add);
        var client = mock(JDA.class);
        org.springframework.test.util.ReflectionTestUtils.setField(bot, "jda", client);
        var sessions = (DiscordStudySessions) org.springframework.test.util.ReflectionTestUtils.getField(bot, "sessions");
        sessions.ready(java.util.List.of(new DiscordStudySessions.Occupant("414964289770881035", "member", "voice")));
        clock.set("2026-10-14T01:05:00Z");
        sessions.checkpoint();
        var stopper = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var stopped = stopper.submit(() -> bot.stop());
            var save = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(save);
            verify(client, never()).shutdown();
            assertFalse(stopped.isDone());
            save.run();
            stopped.get(2, TimeUnit.SECONDS);
            verify(recording).processDiscordAutomatic(any());
            verify(client).shutdown();
            sessions.ready(java.util.List.of(new DiscordStudySessions.Occupant("414964289770881035", "member", "voice")));
            clock.set("2026-10-14T01:10:00Z");
            sessions.update(new DiscordStudySessions.Occupant("414964289770881035", "member", "voice"), false);
            assertTrue(queue.isEmpty());
        } finally { stopper.shutdownNow(); bot.stop(); }
    }
}
