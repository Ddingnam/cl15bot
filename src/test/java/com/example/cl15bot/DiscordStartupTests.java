package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;

class DiscordStartupTests {
    @Test void invalidDiscordMappingDoesNotAbortSharedApplicationContext() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean("mmAvailable", String.class, () -> "ready");
            context.registerBean(DiscordBot.class, () -> new DiscordBot(
                    new DiscordProperties("token", "123456789012345678", "invalid-json"),
                    new BotProperties("mm", "notion", "study", "title", "members"),
                    mock(StudyRecordingService.class), Clock.systemUTC(), Runnable::run));
            assertDoesNotThrow(context::refresh);
            assertEquals("ready", context.getBean("mmAvailable"));
        }
    }

    @Test void transientInitialLoginRetriesWithoutASecondSimultaneousLogin() throws Exception {
        var bot = bot("test-token");
        var retry = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var client = mock(JDA.class);
        doAnswer(call -> {
            assertEquals(30_000L, call.<Long>getArgument(1));
            retry.add(call.getArgument(0)); return null;
        }).when(bot).scheduleRetry(any(), anyLong());
        doThrow(new net.dv8tion.jda.api.exceptions.HttpException("temporary", new java.io.IOException()))
                .doReturn(client).when(bot).buildClient();
        try {
            bot.start();
            var next = retry.poll(2, TimeUnit.SECONDS);
            assertNotNull(next);
            bot.start();
            verify(bot, times(1)).buildClient();
            next.run();
            verify(bot, times(2)).buildClient();
            assertSame(client, org.springframework.test.util.ReflectionTestUtils.getField(bot, "jda"));
        } finally { bot.stop(); }
    }

    @Test void stoppedBotDoesNotScheduleMoreRetries() {
        var bot = bot("");
        bot.start(); bot.stop();
        assertDoesNotThrow(() -> bot.scheduleRetry(() -> fail("Ran after shutdown"), 0));
    }
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
        bot.initialize(new DiscordMemberMapping("{\"414964289770881035\":\"member\"}"));
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
