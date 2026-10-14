package com.example.cl15bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.example.cl15bot.StudyCommandController.CommandResponse;

@Service
public class StudyRecordingService {
    private static final Logger log = LoggerFactory.getLogger(StudyRecordingService.class);
    private final BotProperties properties;
    private final NotionStudyClient notion;
    private final MemberRegistry members;
    private final NotionRetryPolicy retries;
    private final Object[] memberLocks = new Object[64];

    public StudyRecordingService(BotProperties properties, NotionStudyClient notion, MemberRegistry members) {
        this(properties, notion, members, new NotionRetryPolicy());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StudyRecordingService(BotProperties properties, NotionStudyClient notion, MemberRegistry members,
            NotionRetryPolicy retries) {
        this.properties = properties;
        this.notion = notion;
        this.members = members;
        this.retries = retries;
        java.util.Arrays.setAll(memberLocks, i -> new Object());
    }

    public ResponseEntity<CommandResponse> process(StudyEntry entry) {
        return process(entry, false);
    }

    public ResponseEntity<CommandResponse> processMattermost(StudyEntry entry) {
        return process(entry, true);
    }

    public ResponseEntity<CommandResponse> processDiscord(StudyEntry entry, java.time.Instant receivedAt,
            java.time.Clock clock) {
        return process(entry, true, () -> {
            if (!clock.instant().isBefore(receivedAt.plus(java.time.Duration.ofMinutes(10)))) {
                throw new MattermostRequestDeadline.ExpiredException();
            }
        });
    }

    private ResponseEntity<CommandResponse> process(StudyEntry entry, boolean linkToStudyLog) {
        return process(entry, linkToStudyLog, () -> {});
    }

    private ResponseEntity<CommandResponse> process(StudyEntry entry, boolean linkToStudyLog, Runnable beforeStart) {
        if (!properties.hasNotionConfiguration()) {
            return ResponseEntity.ok(failureReply("Notion 연결 설정이 완료되지 않아 저장할 수 없습니다. 관리자에게 설정 확인을 요청해주세요."));
        }
        // Fixed stripes bound memory and serialize preflight + creation across both platforms.
        synchronized (memberLocks[Math.floorMod(entry.memberName().hashCode(), memberLocks.length)]) {
            try { beforeStart.run(); }
            catch (MattermostRequestDeadline.ExpiredException ex) {
                return ResponseEntity.ok(failureReply(ex.getMessage()));
            }
            return retries.withinBudget(() -> processLocked(entry, linkToStudyLog));
        }
    }

    private ResponseEntity<CommandResponse> processLocked(StudyEntry entry, boolean linkToStudyLog) {
        boolean saveStarted = false;
        try {
            // A request can also expire while waiting for the common member lock.
            MattermostRequestDeadline.check();
            String memberPageId = members.pageIdFor(entry.memberName()).orElse(null);
            if (memberPageId == null) {
                return ResponseEntity.ok(failureReply("연결된 이름을 Members에서 찾지 못했습니다. 관리자에게 계정 연결 이름과 Notion Members 이름의 일치 여부를 확인해달라고 요청해주세요."));
            }
            saveStarted = true;
            String pageUrl = linkToStudyLog ? notion.saveToStudyLog(entry, memberPageId)
                    : notion.save(entry, memberPageId);
            log.info("Study request: requestId={} stage=save-completed", MDC.get("studyRequestId"));
            return ResponseEntity.ok(reply("✅ " + display(entry.memberName()) + "님의 공부 기록을 저장했습니다.\n"
                    + (linkToStudyLog ? "\n" : "")
                    + "🕒 " + NotionStudyClient.period(entry.startTime(), entry.endTime()) + " · " + entry.minutes() + "분\n"
                    + "📚 공부 내용 : " + display(entry.subject())
                    + (pageUrl == null || pageUrl.isEmpty() ? "" : (linkToStudyLog ? "\n\n" : "\n") + "🔗 ["
                            + (linkToStudyLog ? "Study Log 전체 기록 확인" : "Notion 기록 확인") + "](<" + pageUrl + ">)")));
        } catch (NotionStudyClient.OverlappingStudyException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (NotionStudyClient.StudyPreflightException ex) {
            log.warn("Notion preflight failure: requestId={} stage={} causes={}",
                    MDC.get("studyRequestId"), ex.stage(), exceptionTypes(ex));
            if (ex.getCause() instanceof RestClientResponseException response) {
                return ResponseEntity.ok(failureReply(notionHttpFailure(response, false)));
            }
            if (ex.getCause() instanceof NotionRetryPolicy.CoolingDownException cooling) {
                return ResponseEntity.ok(failureReply(cooling.getMessage()));
            }
            if (ex.getCause() instanceof MattermostRequestDeadline.ExpiredException expired) {
                return ResponseEntity.ok(failureReply(expired.getMessage()));
            }
            return ResponseEntity.ok(failureReply("Notion 조회를 완료하지 못해 저장 요청을 보내지 않았습니다. 잠시 후 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
        } catch (NotionStudyClient.StudySchemaException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (MemberRegistry.DuplicateMemberException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (MattermostRequestDeadline.ExpiredException ex) {
            log.warn("Study request: requestId={} stage=processing-expired", MDC.get("studyRequestId"));
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (NotionRetryPolicy.CoolingDownException ex) {
            return ResponseEntity.ok(failureReply(ex.getMessage()));
        } catch (RestClientResponseException ex) {
            log.warn("Notion request rejected: requestId={} stage={} HTTP={}",
                    MDC.get("studyRequestId"), saveStarted ? "study-save" : "members-query", ex.getStatusCode().value());
            String message = notionHttpFailure(ex, saveStarted);
            return ResponseEntity.ok(failureReply(message));
        } catch (ResourceAccessException ex) {
            log.warn("Notion network failure: requestId={} stage={} causes={}",
                    MDC.get("studyRequestId"), saveStarted ? "study-save" : "members-query", exceptionTypes(ex));
            if (!saveStarted) {
                return ResponseEntity.ok(failureReply("Notion 연결 문제로 멤버를 조회하지 못했습니다. 저장 요청을 보내지 않았습니다. 잠시 후 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
            }
            return ResponseEntity.ok(failureReply(uncertainSave()));
        } catch (RestClientException ex) {
            log.warn("Notion request failed: requestId={} stage={} causes={}",
                    MDC.get("studyRequestId"), saveStarted ? "study-save" : "members-query", exceptionTypes(ex));
            if (!saveStarted) {
                return ResponseEntity.ok(failureReply("멤버 조회 결과를 확인하지 못해 저장 요청을 보내지 않았습니다. 잠시 후 같은 명령어로 다시 시도하고, 계속 실패하면 관리자에게 문의해주세요."));
            }
            return ResponseEntity.ok(failureReply(uncertainSave()));
        } catch (RuntimeException ex) {
            log.warn("Unexpected study processing failure: requestId={} cause={}",
                    MDC.get("studyRequestId"), ex.getClass().getSimpleName());
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

    private static String notionHttpFailure(RestClientResponseException response, boolean saveStarted) {
        int status = response.getStatusCode().value();
        if (status == 429) {
            if (response.getResponseBodyAsString().contains("public_api_request_blocked")) {
                return "Notion API 접근이 제한되어 저장하지 못했습니다. 관리자에게 Notion 연결 상태 확인을 요청해주세요.";
            }
            String wait = response.getResponseHeaders() == null ? null
                    : response.getResponseHeaders().getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER);
            return "Notion 요청 제한으로 자동 처리를 완료하지 못했습니다. 저장하지 않았습니다. "
                    + (wait != null && wait.matches("[0-9]{1,9}") ? "약 " + wait + "초 후 " : "잠시 후 ")
                    + "같은 명령어로 다시 시도해주세요.";
        }
        return switch (status) {
            case 401, 403 -> "Notion 연결 권한 문제로 저장하지 못했습니다. 관리자에게 Notion 연결 권한 확인을 요청해주세요.";
            case 404 -> "Notion 데이터베이스를 찾을 수 없어 저장하지 못했습니다. 관리자에게 데이터베이스 연결 확인을 요청해주세요.";
            case 400 -> "Notion 기록 항목 설정이 맞지 않아 저장하지 못했습니다. 관리자에게 Study Log 설정 확인을 요청해주세요.";
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

}
