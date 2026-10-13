# Render 배포와 MM 공부 명령 연결

기존 서비스를 사용하며 중복 생성하지 않습니다. 새 서비스 설정은 Ddingnam/cl15bot, master, Docker, Singapore, Free, Health Check Path `/actuator/health`, Auto Deploy On Commit입니다. Build/Start/Docker Command는 별도로 덮어쓰지 않습니다.

## Environment

| 변수 | 값 |
|---|---|
| MATTERMOST_SLASH_TOKEN | 슬래시 명령 Token |
| MATTERMOST_MEMBER_MAPPINGS | MM 사용자 ID와 Members 이름 JSON |
| NOTION_TOKEN | Integration API 토큰 |
| NOTION_DATA_SOURCE_ID | Study Log 원본 ID |
| NOTION_MEMBERS_DATA_SOURCE_ID | Members 원본 ID |

```json
{"첫번째_MM_사용자_ID":"정명남","두번째_MM_사용자_ID":"전배준"}
```

사용자 ID는 `/st 내정보`로 확인합니다. 매칭 등록·변경 후 Save and Deploy합니다. 새 Notion 컬럼·추가 MM API 토큰은 필요 없습니다. MATTERMOST_ALLOWED_CHANNEL_ID와 NOTION_MEMBER_MAPPINGS는 사용하지 않습니다. NOTION_TITLE_PROPERTY는 기본 스터디입니다. local 프로필과 PORT는 직접 등록하지 않습니다.

render.yaml은 다섯 설정을 sync: false로 정의합니다. 기존 서비스에서는 새 변수를 직접 등록합니다. Git push만으로 실제 계정 매칭 값이 등록되지는 않습니다.

## MM 설정 변경

Integrations → Slash Commands → 기존 명령 편집:

```text
Command Trigger Word: st
Request Method: POST
Request URL: https://cl15bot.onrender.com/api/mattermost/study
Autocomplete Hint: <MMDD> <시작 HHmm> [종료 MMDD] <종료 HHmm> <공부 내용>
Autocomplete Description: 2026년 공부 기록 · 이름 자동 연결 · 자정을 넘기면 종료 날짜 필수
```

Trigger Word에는 `/`를 넣지 않습니다. 명령을 st로 바꿔도 Request URL은 그대로입니다. 기존 명령을 편집하면 Token을 유지합니다. 새 명령을 만들면 새 Token과 Render 값을 맞춥니다. 프로젝트 수정만으로 MM 명령 설정은 변경되지 않습니다.

## 배포 후 확인

Live 상태와 `/actuator/health`의 UP을 확인합니다.

```text
/st 도움말
/st 내정보
/st 1013 1900 2030 Java 복습
/st 1013 2300 1014 0100 Java 복습
```

실제 공부한 과거 기록으로 테스트합니다. 내정보의 연결 이름을 먼저 확인합니다. MM 성공 응답에는 해석된 날짜·시각·분·내용과 공용 Study Log 전체 기록 링크가 표시됩니다. 링크를 클릭하면 개별 기록 대신 전체 로그가 열립니다. 추가 환경변수는 필요 없습니다. 이전 긴 형식은 거절합니다. 모든 채널과 DM에서 사용할 수 있습니다.

0230 같은 없는 날짜, 2400 같은 시각, 종료 날짜 없는 역순 시각, 미래 종료, 1440분 초과, 연도 넘김과 미등록 계정은 거절됩니다. 같은 멤버의 부분·동일 구간은 거절하고 끝점만 맞닿으면 허용합니다. 네 자리 숫자로 시작하는 공부 내용 대신 `문제 1234 풀이`처럼 설명을 앞에 씁니다.

정상 입력은 처리 중 안내 후 최종 결과를 비공개 전송합니다. Discord 연결은 아직 구현하지 않았습니다.

## 재배포·진단

master push 후 On Commit 자동 배포 또는 Manual Deploy → Deploy latest commit으로 배포합니다. 결과가 없으면 재입력 전에 Notion을 확인합니다. callback URL에는 인증 정보가 있으므로 공유하지 않습니다.

| 증상 | 확인 |
|---|---|
| Maven Central 429 빌드 실패 | Google 공식 Maven Central 미러를 우선 사용하도록 설정됨. 플러그인과 앱 의존성 모두 적용. 반복 재시도·캐시 삭제를 피하고, 실패한 다운로드 URL 확인 |
| 도움말도 없음 | 서버 UP, POST/URL/Token, MM 오류 확인 |
| 계정 연결 없음 | 내정보 ID와 매칭 JSON 키 확인 |
| 연결 이름 없음/중복 | 매칭 이름과 Members 제목 확인 |
| Notion 400 | 기존 속성 이름·타입 |
| Notion 401/403/404 | Integration 권한과 두 원본 ID |
| 처리 중 이후 결과 없음 | Notion 저장 확인, Mattermost final response delivery failed 로그 |
| 입력 형식 실패 | MMDD·HHmm 네 자리와 필수 내용 |

Render Free는 들어오는 요청이 15분간 없으면 절전하며 첫 요청이 지연될 수 있습니다. health를 먼저 확인합니다. 작업이 메모리에 있어 강제 종료·재배포 중 유실될 수 있고 동시 저장 중복도 완전히 막지 못합니다. callback 302 실패 감지 문제도 별도 보완 대상으로 남아 있습니다.

공식 문서: https://render.com/docs/deploys · https://render.com/docs/free · https://developers.mattermost.com/integrate/slash-commands/custom/
