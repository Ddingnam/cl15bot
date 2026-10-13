package com.example.cl15bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "discord")
public record DiscordProperties(String botToken, String guildId, String memberMappings) {
    public boolean enabled() { return botToken != null && !botToken.isBlank(); }
}
