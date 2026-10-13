package com.example.cl15bot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.stereotype.Component;

@Component
public class MattermostMemberMapping {
    private final Map<String, String> names;
    public MattermostMemberMapping(@Value("${bot.mattermost-member-mappings:}") String json) {
        Map<String, String> parsed = new LinkedHashMap<>();
        try {
            if (json != null && !json.isBlank()) {
                for (var entry : JsonParserFactory.getJsonParser().parseMap(json).entrySet()) {
                    if (entry.getKey().isBlank() || entry.getKey().length() > 200
                            || !(entry.getValue() instanceof String name) || name.isBlank() || name.length() > 200) throw new IllegalArgumentException();
                    parsed.put(entry.getKey(), name.strip());
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("MATTERMOST_MEMBER_MAPPINGS는 사용자 ID와 Members 이름을 연결한 JSON 객체여야 합니다.");
        }
        names = Map.copyOf(parsed);
    }
    public Optional<String> nameFor(String userId) { return Optional.ofNullable(names.get(userId)); }
}
