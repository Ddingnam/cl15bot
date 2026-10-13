package com.example.cl15bot;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local & !prod")
public class LocalTestController {
    private final BotProperties properties;

    public LocalTestController(BotProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/local-test")
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(new ClassPathResource("local-test.html"));
    }

    @GetMapping("/local-test/status")
    public Map<String, Boolean> status() {
        return Map.of("slashTokenConfigured", properties.hasMattermostToken(),
                "notionConfigured", properties.hasNotionConfiguration(),
                "membersConfigured", properties.hasMembersConfiguration());
    }
}
