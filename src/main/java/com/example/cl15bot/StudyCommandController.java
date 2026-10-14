package com.example.cl15bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StudyCommandController {
    private static final Logger log = LoggerFactory.getLogger(StudyCommandController.class);
    private final BotProperties properties;
    private final StudyRecordingService recording;
    private final Clock clock;
    private final MattermostResponseSender responseSender;
    private final Executor executor;
    private final MattermostMemberMapping mapping;

    public StudyCommandController(BotProperties properties, NotionStudyClient notion, Clock clock,
            MemberRegistry members, MattermostResponseSender responseSender,
            @Qualifier("studyExecutor") Executor executor, MattermostMemberMapping mapping) {
        this.properties = properties;
        this.recording = new StudyRecordingService(properties, notion, members);
        this.clock = clock;
        this.responseSender = responseSender;
        this.executor = executor;
        this.mapping = mapping;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StudyCommandController(BotProperties properties, Clock clock,
            MattermostResponseSender responseSender, @Qualifier("studyExecutor") Executor executor,
            MattermostMemberMapping mapping, StudyRecordingService recording) {
        this.properties = properties;
        this.clock = clock;
        this.responseSender = responseSender;
        this.executor = executor;
        this.mapping = mapping;
        this.recording = recording;
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
            String memberName = mapping.nameFor(userId).orElse(null);
            return ResponseEntity.ok(reply("**🆔 Mattermost 사용자 ID**\n`" + userId + "`\n\n"
                    + "**📚 연결된 Members 이름**\n" + (memberName == null ? "미등록" : memberName)
                    + (memberName == null ? "\n\n**📌 계정 연결 안내**\n관리자에게 위 사용자 ID와 연결할 Members 이름을 전달해주세요." : "")));
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
            responseUri = MattermostResponseSender.responseUri(responseUrl, properties.mattermostBaseUrl());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(failureReply("Mattermost 결과 전송 주소를 확인하지 못했습니다. 저장하지 않았습니다. 관리자에게 명령어 연결 확인을 요청해주세요."));
        }
        String requestId = UUID.randomUUID().toString();
        var receivedAt = clock.instant();
        try {
            log.info("Study request: requestId={} stage=accepting", requestId);
            executor.execute(() -> {
                try (var context = MDC.putCloseable("studyRequestId", requestId)) {
                    log.info("Study request: requestId={} stage=processing", requestId);
                    CommandResponse result;
                    try {
                        result = MattermostRequestDeadline.within(clock, receivedAt, () -> process(entry).getBody());
                    } catch (MattermostRequestDeadline.ExpiredException ex) {
                        log.warn("Study request: requestId={} stage=processing-expired", requestId);
                        result = failureReply(ex.getMessage());
                    }
                    log.info("Study request: requestId={} stage=result-ready outcome={}", requestId,
                            result != null && result.text().startsWith("✅") ? "saved" : "not-confirmed");
                    if (clock.instant().isBefore(receivedAt.plus(MattermostRequestDeadline.CALLBACK_LIMIT))) {
                        responseSender.send(responseUri, result);
                    } else {
                        log.warn("Study request: requestId={} stage=callback-expired; result not delivered", requestId);
                    }
                }
            });
            log.info("Study request: requestId={} stage=accepted", requestId);
        } catch (RejectedExecutionException ex) {
            log.warn("Study request: requestId={} stage=queue-rejected", requestId);
            return ResponseEntity.ok(failureReply("현재 처리 중인 요청이 많아 접수하지 못했습니다. 저장 요청을 보내지 않았습니다. 잠시 후 다시 입력해주세요."));
        }
        String studyLogUrl = NotionStudyClient.pageLink(null, properties.notionStudyLogUrl());
        return ResponseEntity.ok(reply("⏳ 공부 기록을 처리 중입니다.\n\n"
                + "1~2분 후에도 결과가 없으면 아래 Study Log에서\n본인 기록을 확인해주세요.\n\n"
                + "이미 저장됐을 수 있으니 확인 전에는 다시 입력하지 마세요."
                + (studyLogUrl.isEmpty() ? "" : "\n\n🔗 [Study Log 전체 기록 확인](<" + studyLogUrl + ">)")));
    }

    private ResponseEntity<CommandResponse> process(StudyEntry entry) {
        return recording.processMattermost(entry);
    }

    private static CommandResponse failureReply(String text) { return reply(StudyEntry.inputError(text)); }
    private static CommandResponse reply(String text) { return new CommandResponse("ephemeral", text); }

    public record CommandResponse(String response_type, String text) { }
}
