package com.example.cl15bot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(String mattermostToken, String notionToken,
        String notionDataSourceId, String notionTitleProperty, String notionMembersDataSourceId,
        String mattermostAllowedChannelId) {
    @ConstructorBinding
    public BotProperties { }

    public BotProperties(String mattermostToken, String notionToken, String notionDataSourceId,
            String notionTitleProperty, String notionMembersDataSourceId) {
        this(mattermostToken, notionToken, notionDataSourceId, notionTitleProperty,
                notionMembersDataSourceId, "");
    }

    public boolean allowsChannel(String channelId) {
        return hasAllowedChannel()
                && mattermostAllowedChannelId.equals(channelId);
    }

    public boolean hasAllowedChannel() {
        return mattermostAllowedChannelId != null && !mattermostAllowedChannelId.isBlank();
    }

    public boolean hasMattermostToken() {
        return mattermostToken != null && !mattermostToken.isBlank();
    }

    public boolean hasNotionConfiguration() {
        return notionToken != null && !notionToken.isBlank()
                && notionDataSourceId != null && !notionDataSourceId.isBlank()
                && hasMembersConfiguration()
                && notionTitleProperty != null && !notionTitleProperty.isBlank();
    }

    public boolean hasMembersConfiguration() {
        return notionMembersDataSourceId != null && !notionMembersDataSourceId.isBlank();
    }
}
