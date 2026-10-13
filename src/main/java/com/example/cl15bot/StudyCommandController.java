package com.example.cl15bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;

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
    private final MattermostResponseSender responseSender;
    private final Executor executor;
    private final MattermostMemberMapping mapping;

    public StudyCommandController(BotProperties properties, NotionStudyClient notion, Clock clock,
            MemberRegistry members, MattermostResponseSender responseSender,
            @Qualifier("studyExecutor") Executor executor, MattermostMemberMapping mapping) {
        this.properties = properties;
        this.notion = notion;
        this.clock = clock;
        this.members = members;
        this.responseSender = responseSender;
        this.executor = executor;
        this.mapping = mapping;
    }

    @PostMapping(value = "/api/mattermost/study", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CommandResponse> study(
            @RequestParam(defaultValue = "") String token,
            @RequestParam(defaultValue = "") String text,
            @RequestParam(name = "user_id", defaultValue = "") String userId,
            @RequestParam(name = "user_name", defaultValue = "") String userName,
            @RequestParam(name = "channel_id", defaultValue = "") String channelId,
            @RequestParam(name = "response_url", defaultValue = "") String responseUrl) {
        if (!properties.hasMattermostToken()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(failureReply("봇 설정이 완료되지 않아 저장할 수 없습니다. 관리자에게 봇 설정 확인을 요청해주세요."));
        }
        if (!MessageDigest.isEqual(properties.mattermostToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(failureReply("명령어 인증에 실패했습니다. 관리자에게 Mattermost 명령어 연결 확인을 요청해주세요."));
        }
        if (text.isBlank() || text.strip().equalsIgnoreCase("help") || text.strip().equals("도움말")) {
            return ResponseEntity.ok(reply(StudyEntry.HELP));
        }
        if (text.strip().equalsIgnoreCase("whoami") || text.strip().equals("내정보")) {
            if (userId.isBlank() || userId.length() > 200) {
                return ResponseEntity.ok(failureReply("Mattermost 사용자 정보를 확인하지 못했습니다. Mattermost에서 다시 실행하고, 계속 실패하면 관리자에게 문의해주세요."));
            }
            return ResponseEntity.ok(reply("Mattermost 사용자 ID: " + userId
                    + "\n연결된 Members 이름: " + mapping.nameFor(userId).orElse("미등록")
                    + "\n미등록이면 관리자에게 이 사용자 ID와 Members 이름을 전달해주세요."));
        }
        StudyEntry entry;
        try {
            entry = StudyEntry.parse(text, mapping.nameFor(userId).orElse(null), userId, userName, channelId, ZonedDateTime.now(clock));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        }
        if (!properties.hasNotionConfiguration()) {
            return ResponseEntity.ok(failureReply("Notion 연결 설정이 완료되지 않아 저장할 수 없습니다. 관리자에게 Notion 연결 설정 확인을 요청해주세요."));
        }
        // Local clients without a Mattermost callback receive the final result directly.
        if (responseUrl.isBlank()) return process(entry);
        URI responseUri;
        try {
            responseUri = MattermostResponseSender.responseUri(responseUrl);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(failureReply("Mattermost 결과 전송 주소를 확인하지 못했습니다. 저장하지 않았습니다. 관리자에게 명령어 연결 확인을 요청해주세요."));
        }
        try {
            executor.execute(() -> responseSender.send(responseUri, process(entry).getBody()));
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.ok(failureReply("현재 처리 중인 요청이 많아 접수하지 못했습니다. 저장 요청을 보내지 않았습니다. 잠시 후 다시 입력해주세요."));
        }
        return ResponseEntity.ok(reply("⏳ 공부 기록을 처리 중입니다. 완료되면 결과를 알려드릴게요.\n결과가 오지 않으면 재입력 전에 Notion에서 저장 여부를 확인해주세요."));
    }

    private ResponseEntity<CommandResponse> process(StudyEntry entry) {
        boolean saveStarted = false;
        try {
            String memberPageId = members.pageIdFor(entry.memberName()).orElse(null);
            if (memberPageId == null) {
                return ResponseEntity.ok(failureReply("연결된 이름을 Members에서 찾지 못했습니다. 관리자에게 계정 연결 이름과 Notion Members 이름의 일치 여부를 확인해달라고 요청해주세요."));
            }
            saveStarted = true;
            String pageUrl = notion.save(entry, memberPageId);
            return ResponseEntity.ok(reply("✅ " + display(entry.memberName()) + "님의 공부 기록을 저장했습니다.\n"
                    + "🕒 " + NotionStudyClient.period(entry.startTime(), entry.endTime()) + " · " + entry.minutes() + "분\n"
                    + "📚 공부 내용 : " + display(entry.subject())
                    + (pageUrl == null || pageUrl.isEmpty() ? "" : "\n🔗 [Notion 기록 확인](<" + pageUrl + ">)")));
        } catch (NotionStudyClient.OverlappingStudyException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (NotionStudyClient.StudyPreflightException ex) {
            log.warn("Notion preflight failure: stage={} causes={}", ex.stage(), exceptionTypes(ex));
            if (ex.getCause() instanceof RestClientResponseException response) {
                return ResponseEntity.ok(failureReply(notionHttpFailure(response.getStatusCode().value(), false)));
            }
            return ResponseEntity.ok(failureReply("Notion 조회를 완료하지 못해 저장 요청을 보내지 않았습니다. 잠시 후 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
        } catch (NotionStudyClient.StudySchemaException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (MemberRegistry.DuplicateMemberException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (RestClientResponseException ex) {
            log.warn("Notion request rejected: stage={} HTTP={}",
                    saveStarted ? "study-save" : "members-query", ex.getStatusCode().value());
            String message = notionHttpFailure(ex.getStatusCode().value(), saveStarted);
            return ResponseEntity.ok(failureReply(message));
        } catch (ResourceAccessException ex) {
            log.warn("Notion network failure: stage={} causes={}",
                    saveStarted ? "study-save" : "members-query", exceptionTypes(ex));
            if (!saveStarted) {
                return ResponseEntity.ok(failureReply("Notion 연결 문제로 멤버를 조회하지 못했습니다. 저장 요청을 보내지 않았습니다. 잠시 후 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
            }
            return ResponseEntity.ok(failureReply(uncertainSave()));
        } catch (RestClientException ex) {
            log.warn("Notion request failed: stage={} causes={}",
                    saveStarted ? "study-save" : "members-query", exceptionTypes(ex));
            if (!saveStarted) {
                return ResponseEntity.ok(failureReply("멤버 조회 결과를 확인하지 못해 저장 요청을 보내지 않았습니다. 잠시 후 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
            }
            return ResponseEntity.ok(failureReply(uncertainSave()));
        } catch (RuntimeException ex) {
            log.warn("Unexpected study processing failure: cause={}", ex.getClass().getSimpleName());
            return ResponseEntity.ok(failureReply(saveStarted ? uncertainSave()
                    : "기록 처리 중 문제가 발생해 저장 요청을 보내지 않았습니다. 잠시 후 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
        }
    }

    private static CommandResponse failureReply(String text) {
        return reply(StudyEntry.inputError(text));
    }

    private static String display(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String uncertainSave() {
        return "저장 완료 여부를 확인하지 못했습니다. 재입력 전에 Notion에서 해당 멤버·시간의 기록을 확인해주세요. 기록이 없으면 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요.";
    }

    private static String notionHttpFailure(int status, boolean saveStarted) {
        return switch (status) {
            case 401, 403 -> "Notion 연결 권한 문제로 저장하지 못했습니다. 관리자에게 Notion 연결 권한 확인을 요청해주세요.";
            case 404 -> "Notion 데이터베이스를 찾을 수 없어 저장하지 못했습니다. 관리자에게 데이터베이스 연결 확인을 요청해주세요.";
            case 400 -> "Notion 기록 항목 설정이 맞지 않아 저장하지 못했습니다. 관리자에게 Study Log 설정 확인을 요청해주세요.";
            case 429 -> "Notion 요청이 많아 저장하지 못했습니다. 잠시 후 같은 명령어로 다시 시도해주세요.";
            default -> saveStarted ? uncertainSave()
                    : "Notion 조회 오류로 저장 요청을 보내지 않았습니다. 잠시 후 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요.";
        };
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
