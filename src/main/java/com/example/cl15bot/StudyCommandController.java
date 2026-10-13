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
                    + "\n공부 기록에는 Members의 기존 이름을 직접 입력합니다.\n예: /study 60 정명남 | Java 복습"));
        }
        StudyEntry entry;
        try {
            entry = StudyEntry.parse(text, userId, userName, channelId, ZonedDateTime.now(clock));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(reply(ex.getMessage()));
        }
        if (!properties.hasNotionConfiguration()) {
            return ResponseEntity.ok(reply("관리자가 NOTION_TOKEN, NOTION_DATA_SOURCE_ID, NOTION_MEMBERS_DATA_SOURCE_ID와 제목 속성을 설정해야 합니다."));
        }
        boolean saveStarted = false;
        try {
            String memberPageId = members.pageIdFor(entry.memberName()).orElse(null);
            if (memberPageId == null) {
                return ResponseEntity.ok(reply("Members에서 해당 이름을 찾지 못했습니다. 기존 멤버 페이지의 이름을 정확히 입력해주세요.\n" + StudyEntry.HELP));
            }
            saveStarted = true;
            notion.save(entry, memberPageId);
            DateTimeFormatter format = DateTimeFormatter.ofPattern("MM-dd HH:mm");
            return ResponseEntity.ok(reply("✅ 공부 " + entry.minutes() + "분을 Notion에 저장했습니다.\n"
                    + entry.startTime().format(format) + " ~ " + entry.endTime().format(format) + " (한국 시간)"));
        } catch (MemberRegistry.DuplicateMemberException ex) {
            return ResponseEntity.ok(reply(ex.getMessage()));
        } catch (RestClientResponseException ex) {
            log.warn("Notion request rejected: stage={} HTTP={}",
                    saveStarted ? "study-save" : "members-query", ex.getStatusCode().value());
            String message = switch (ex.getStatusCode().value()) {
                case 401, 403 -> "Notion 토큰 또는 데이터베이스 연결 권한을 확인해주세요.";
                case 404 -> "Notion 데이터 소스 ID와 데이터베이스 연결 권한을 확인해주세요.";
                case 400 -> "Notion 속성 이름과 타입을 README의 표에 맞춰주세요.";
                case 429 -> "Notion 요청이 많습니다. 잠시 후 다시 입력해주세요.";
                default -> "Notion 오류로 저장 결과를 확인하지 못했습니다. 재입력 전에 데이터베이스를 확인해주세요.";
            };
            return ResponseEntity.ok(reply(message));
        } catch (ResourceAccessException ex) {
            log.warn("Notion network failure: stage={} causes={}",
                    saveStarted ? "study-save" : "members-query", exceptionTypes(ex));
            if (!saveStarted) {
                return ResponseEntity.ok(reply("Members 조회 중 Notion 연결 시간 초과 또는 통신 오류가 발생했습니다. 공부 기록 저장 요청은 보내지 않았습니다. 관리자에게 Render Logs의 'Notion network failure' 확인을 요청해주세요."));
            }
            return ResponseEntity.ok(reply("Study Log 저장 중 Notion 연결 시간 초과 또는 통신 오류가 발생했습니다. 중복을 피하려면 재입력 전에 데이터베이스를 확인해주세요."));
        } catch (RestClientException ex) {
            log.warn("Notion request failed: stage={} causes={}",
                    saveStarted ? "study-save" : "members-query", exceptionTypes(ex));
            if (!saveStarted) {
                return ResponseEntity.ok(reply("Members 조회 결과를 확인하지 못했습니다. 공부 기록 저장 요청은 보내지 않았습니다."));
            }
            return ResponseEntity.ok(reply("저장 결과를 확인하지 못했습니다. 재입력 전에 Notion을 확인해주세요."));
        }
    }

    private static CommandResponse reply(String text) {
        return new CommandResponse("ephemeral", text);
    }

    private static String exceptionTypes(Throwable failure) {
        StringBuilder types = new StringBuilder();
        for (int depth = 0; failure != null && depth < 8; depth++) {
            if (!types.isEmpty()) {
                types.append(" -> ");
            }
            types.append(failure.getClass().getSimpleName());
            failure = failure.getCause();
        }
        return types.toString();
    }

    public record CommandResponse(String response_type, String text) { }
}
