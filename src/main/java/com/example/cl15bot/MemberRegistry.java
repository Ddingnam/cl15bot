package com.example.cl15bot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class MemberRegistry {
    private final Map<String, String> members;

    public MemberRegistry(BotProperties properties) {
        Map<String, String> parsed = new HashMap<>();
        String configured = properties.notionMemberMappings();
        if (configured != null && !configured.isBlank()) {
            for (String mapping : configured.split(",", -1)) {
                String[] parts = mapping.strip().split("=", -1);
                if (parts.length != 2 || !parts[0].strip().matches("[a-zA-Z0-9_-]{1,200}")) {
                    throw invalidConfiguration();
                }
                String pageId = parts[1].strip().replace("-", "");
                if (!pageId.matches("[a-fA-F0-9]{32}")) {
                    throw invalidConfiguration();
                }
                String normalized = UUID.fromString(pageId.substring(0, 8) + "-" + pageId.substring(8, 12)
                        + "-" + pageId.substring(12, 16) + "-" + pageId.substring(16, 20)
                        + "-" + pageId.substring(20)).toString();
                if (parsed.putIfAbsent(parts[0].strip(), normalized) != null) {
                    throw invalidConfiguration();
                }
            }
        }
        members = Map.copyOf(parsed);
    }

    public Optional<String> pageIdFor(String userId) {
        return Optional.ofNullable(members.get(userId));
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("NOTION_MEMBER_MAPPINGS는 중복 없이 Mattermost사용자ID=Notion회원페이지ID를 쉼표로 구분해 설정해야 합니다.");
    }
}
