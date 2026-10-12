package com.example.cl15bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(String mattermostToken, String notionToken,
        String notionDataSourceId, String notionTitleProperty, String notionMemberMappings) {
    public boolean hasMattermostToken() {
        return mattermostToken != null && !mattermostToken.isBlank();
    }

    public boolean hasNotionConfiguration() {
        return notionToken != null && !notionToken.isBlank()
                && notionDataSourceId != null && !notionDataSourceId.isBlank()
                && notionTitleProperty != null && !notionTitleProperty.isBlank();
    }
}
