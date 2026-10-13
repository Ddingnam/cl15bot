package com.example.cl15bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(String mattermostToken, String notionToken,
        String notionDataSourceId, String notionTitleProperty, String notionMembersDataSourceId,
        String mattermostBaseUrl, String notionStudyLogUrl) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public BotProperties { }

    public BotProperties(String mattermostToken, String notionToken, String notionDataSourceId,
            String notionTitleProperty, String notionMembersDataSourceId) {
        this(mattermostToken, notionToken, notionDataSourceId, notionTitleProperty, notionMembersDataSourceId, "", "");
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
