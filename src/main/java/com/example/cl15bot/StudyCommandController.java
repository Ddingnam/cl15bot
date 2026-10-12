package com.example.cl15bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@RestController
public class StudyCommandController {
    private static final Logger log = LoggerFactory.getLogger(StudyCommandController.class);
    private final BotProperties properties;
    private final NotionStudyClient notion;
    private final Clock clock;
    private final MemberRegistry members;

    public StudyCommandController(BotProperties properties, NotionStudyClient notion, Clock clock,
            MemberRegistry members) {
        this.properties = properties;
        this.notion = notion;
        this.clock = clock;
        this.members = members;
    }

    @PostMapping(value = "/api/mattermost/study", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CommandResponse> study(
            @RequestParam(defaultValue = "") String token,
            @RequestParam(defaultValue = "") String text,
            @RequestParam(name = "user_id", defaultValue = "") String userId,
            @RequestParam(name = "user_name", defaultValue = "") String userName,
            @RequestParam(name = "channel_id", defaultValue = "") String channelId) {
        if (!properties.hasMattermostToken()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(reply("관리자가 MATTERMOST_SLASH_TOKEN을 설정해야 합니다."));
        }
        if (!MessageDigest.isEqual(properties.mattermostToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(reply("인증에 실패했습니다."));
        }
        if (text.isBlank() || text.strip().equalsIgnoreCase("help") || text.strip().equals("도움말")) {
            return ResponseEntity.ok(reply(StudyEntry.HELP));
        }
        if (text.strip().equalsIgnoreCase("whoami")) {
            if (userId.isBlank() || userId.length() > 200) {
                return ResponseEntity.ok(reply("Mattermost 사용자 ID가 없습니다."));
            }
            return ResponseEntity.ok(reply("Mattermost 사용자 ID: " + userId
                    + "\n관리자가 이 ID를 Members의 회원 페이지와 연결해야 합니다."));
        }
        StudyEntry entry;
        try {
            entry = StudyEntry.parse(text, userId, userName, channelId, ZonedDateTime.now(clock));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(reply(ex.getMessage()));
        }
        if (!properties.hasNotionConfiguration()) {
            return ResponseEntity.ok(reply("관리자가 NOTION_TOKEN, NOTION_DATA_SOURCE_ID와 제목 속성을 설정해야 합니다."));
        }
        String memberPageId = members.pageIdFor(userId).orElse(null);
        if (memberPageId == null) {
            return ResponseEntity.ok(reply("등록되지 않은 회원입니다. /study whoami로 ID를 확인하고 관리자에게 회원 연결을 요청해주세요."));
        }
        try {
            notion.save(entry, memberPageId);
            DateTimeFormatter format = DateTimeFormatter.ofPattern("MM-dd HH:mm");
            return ResponseEntity.ok(reply("✅ 공부 " + entry.minutes() + "분을 Notion에 저장했습니다.\n"
                    + entry.startTime().format(format) + " ~ " + entry.endTime().format(format) + " (한국 시간)"));
        } catch (RestClientResponseException ex) {
            log.warn("Notion rejected study entry: HTTP {}", ex.getStatusCode().value());
            String message = switch (ex.getStatusCode().value()) {
                case 401, 403 -> "Notion 토큰 또는 데이터베이스 연결 권한을 확인해주세요.";
                case 404 -> "Notion 데이터 소스 ID와 데이터베이스 연결 권한을 확인해주세요.";
                case 400 -> "Notion 속성 이름과 타입을 README의 표에 맞춰주세요.";
                case 429 -> "Notion 요청이 많습니다. 잠시 후 다시 입력해주세요.";
                default -> "Notion 오류로 저장 결과를 확인하지 못했습니다. 재입력 전에 데이터베이스를 확인해주세요.";
            };
            return ResponseEntity.ok(reply(message));
        } catch (ResourceAccessException ex) {
            log.warn("Notion request timed out or connection failed");
            return ResponseEntity.ok(reply("Notion 연결 시간 초과 또는 통신 오류입니다. 중복을 피하려면 재입력 전에 데이터베이스를 확인해주세요."));
        } catch (RestClientException ex) {
            log.warn("Notion request failed");
            return ResponseEntity.ok(reply("저장 결과를 확인하지 못했습니다. 재입력 전에 Notion을 확인해주세요."));
        }
    }

    private static CommandResponse reply(String text) {
        return new CommandResponse("ephemeral", text);
    }

    public record CommandResponse(String response_type, String text) { }
}
