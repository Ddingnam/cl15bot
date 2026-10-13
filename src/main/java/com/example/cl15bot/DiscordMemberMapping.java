package com.example.cl15bot;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.json.JsonParserFactory;

public class DiscordMemberMapping {
    private final Map<String, String> names;

    public DiscordMemberMapping(String json) {
        Map<String, String> parsed = new LinkedHashMap<>();
        var uniqueNames = new HashSet<String>();
        try {
            if (json != null && !json.isBlank()) {
                for (var entry : JsonParserFactory.getJsonParser().parseMap(json).entrySet()) {
                    if (!entry.getKey().matches("[0-9]{17,20}")
                            || !(entry.getValue() instanceof String name) || name.isBlank()
                            || name.length() > 200 || !uniqueNames.add(name.strip())) {
                        throw new IllegalArgumentException();
                    }
                    parsed.put(entry.getKey(), name.strip());
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("DISCORD_MEMBER_MAPPINGS는 사용자 ID → Members 이름 JSON이어야 하며, 멤버당 계정 하나만 허용합니다.");
        }
        names = Map.copyOf(parsed);
    }

    public Optional<String> nameFor(String userId) { return Optional.ofNullable(names.get(userId)); }
}
