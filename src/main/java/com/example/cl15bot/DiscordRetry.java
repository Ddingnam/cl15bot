package com.example.cl15bot;

import java.io.IOException;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HttpException;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;

final class DiscordRetry {
    static long delay(Throwable failure, int failures) {
        long backoff = switch (Math.min(failures, 3)) {
            case 0 -> 30_000; case 1 -> 60_000; case 2 -> 120_000; default -> 300_000;
        };
        boolean transientFailure = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidTokenException || cause instanceof PermissionException
                    || cause instanceof IllegalArgumentException) return -1;
            if (cause instanceof RateLimitedException limited) return Math.max(backoff, limited.getRetryAfter());
            if (cause instanceof ErrorResponseException error) {
                var response = error.getResponse();
                if (response == null) return -1;
                if (response.code == 429) return Math.max(backoff, response.retryAfter);
                return response.code >= 500 || response.code == -1 ? backoff : -1;
            }
            if (cause instanceof IOException || cause instanceof HttpException) transientFailure = true;
        }
        return transientFailure ? backoff : -1;
    }
}
