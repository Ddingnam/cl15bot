package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.exceptions.HttpException;

class DiscordRetryTests {
    @Test void transientFailuresBackOffAndThenStayAtFiveMinutes() {
        long[] expected = {30_000, 60_000, 120_000, 300_000, 300_000};
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], DiscordRetry.delay(new HttpException("network", new IOException()), index));
        }
    }
    @Test void invalidConfigurationAndUnknownFailuresDoNotRetry() {
        assertEquals(-1, DiscordRetry.delay(new IllegalArgumentException(), 0));
        assertEquals(-1, DiscordRetry.delay(new IllegalStateException(), 0));
        assertEquals(-1, DiscordRetry.delay(new HttpException("wrapped", new IllegalArgumentException()), 0));
    }
    @Test void rateLimitLongerThanBackoffIsHonored() {
        assertEquals(1_000_000, DiscordRetry.delay(new RuntimeException(new RateLimitedException("route", 1_000_000)), 3));
    }
}
