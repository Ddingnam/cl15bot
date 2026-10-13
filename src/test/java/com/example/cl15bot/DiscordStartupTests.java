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
}
