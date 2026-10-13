package com.example.cl15bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(String mattermostToken, String notionToken,
        String notionDataSourceId, String notionTitleProperty, String notionMembersDataSourceId) {

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
