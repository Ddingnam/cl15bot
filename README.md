# cl15bot

Java 21 · Spring Boot 4.1.1 · Gradle 기반 Mattermost → Notion 공부 기록 봇입니다. 현재 운영 연도는 **2026년 고정**, 시간대는 Asia/Seoul입니다.

## 사용자 명령어

```text
/st 1013 1900 2030 Java 복습
/st 1013 2300 1014 0100 Java 복습
/st 도움말
/st 내정보
```

같은 날은 `/st <MMDD> <시작 HHmm> <종료 HHmm> <공부 내용>`, 다른 날은 `/st <MMDD> <시작 HHmm> <종료 MMDD> <종료 HHmm> <공부 내용>`입니다. 이름과 구분자 `|`는 입력하지 않습니다. 이름은 MM 사용자 ID와 연결된 기존 Notion Members 이름을 사용합니다. 모든 채널과 DM에서 실행 가능하고 응답은 실행한 사용자에게만 표시됩니다.

종료 날짜를 생략하면 시작일과 같은 날짜입니다. 종료 시각이 빠르다고 다음 날로 넘기지 않습니다. 자정을 넘기면 종료 날짜를 명시합니다. 같은 날에도 종료 날짜를 적을 수 있습니다. `1231 → 0101`처럼 연도를 넘기는 기록은 지원하지 않습니다. 이전의 긴 날짜·시간·이름 입력 형식은 거절합니다.

네 번째 항목이 숫자 네 자리면 종료 날짜를 포함한 형식으로 해석합니다. 공부 내용이 네 자리 숫자로 시작하면 날짜·시간으로 해석될 수 있으므로 `문제 1234 풀이`처럼 설명을 먼저 씁니다.

## 입력 검증

- 전체 입력 2000자 이내, 제어 문자 거절.
- 날짜·시작·종료·공부 내용 누락 확인.
- 숫자 네 자리, 2026년에 존재하는 날짜, 00:00~23:59의 시각 확인.
- 종료 일시는 시작보다 늦어야 하며 공부 시간은 1~1440분.
- 미래 종료와 다음 연도로 넘어가는 기록 거절.
- MM 사용자·채널 정보, 계정 매칭, Members 이름 존재·중복 확인.
- 같은 멤버의 시간 구간이 일부라도 겹치면 내용과 관계없이 거절. 끝점만 맞닿으면 허용.
- 누락·형식 오류는 경고 이유와 둘째 줄 예시. 다른 실패는 예시 없이 사용자 행동 안내.

## 이름 자동 매칭과 환경변수

관리자가 `MATTERMOST_MEMBER_MAPPINGS`를 JSON 객체로 등록합니다. 키는 닉네임이 아닌 **MM 사용자 ID**, 값은 **기존 Members 이름**입니다.

```json
{"첫번째_MM_사용자_ID":"정명남","두번째_MM_사용자_ID":"전배준"}
```

사용자는 `/st 내정보`로 ID와 연결 상태를 확인합니다. 관리자는 매칭 값을 등록하고 재배포합니다. Notion에 새 사용자 ID 컬럼을 추가하지 않습니다. 매칭은 환경변수에 보관되어 재배포 후 유지됩니다. 잘못된 JSON 설정은 시작 시 오류를 알립니다. 미설정이면 도움말·내정보는 가능하지만 기록은 저장하지 않습니다. 최초 1회 사용자 연결 명령이나 프로필 이름 자동 추측은 구현하지 않았습니다.

| 환경변수 | 값 |
|---|---|
| MATTERMOST_SLASH_TOKEN | 슬래시 명령 Token |
| MATTERMOST_MEMBER_MAPPINGS | MM 사용자 ID → Members 이름 JSON |
| NOTION_TOKEN | Notion Integration API 토큰 |
| NOTION_DATA_SOURCE_ID | 공용 Study Log 원본 ID |
| NOTION_MEMBERS_DATA_SOURCE_ID | Members 원본 ID |

선택 변수 `NOTION_TITLE_PROPERTY`는 기본 `스터디`입니다. `.env.example`은 예시이며 `.env`를 자동 로드하지 않습니다. 기존 `MATTERMOST_ALLOWED_CHANNEL_ID`, `NOTION_MEMBER_MAPPINGS`는 사용하지 않습니다.

## 기존 Notion 구조

공용 Study Log에 `스터디` 제목, `날짜`, `이름` 관계, `시작 시간`, `종료 시간`을 저장합니다. 날짜는 시작일입니다. Member 페이지의 Study Log는 필터 보기이며 멤버마다 새 DB를 만들지 않습니다. Integration에 두 원본 읽기·접근 권한과 Study Log 삽입 권한이 필요합니다.

속성 이름 앞뒤 공백은 비교할 때만 제거하고 실제 이름으로 기록합니다. 기존 수식·롤업·랭킹은 수정하지 않습니다. 저장 전에 속성 타입과 시간 겹침을 조회합니다. 조건은 `기존 시작 < 새 종료 AND 기존 종료 > 새 시작`입니다. 시간이 없는 기존 행은 겹침 검사에서 제외됩니다.

## 응답

```text
⏳ 공부 기록을 처리 중입니다. 완료되면 결과를 알려드릴게요.
결과가 오지 않으면 재입력 전에 Notion에서 저장 여부를 확인해주세요.
```

```text
✅ 정명남님의 공부 기록을 저장했습니다.
🕒 2026-10-13 23:00 ~ 2026-10-14 01:00 · 120분
📚 공부 내용 : Java 복습
🔗 Study Log 전체 기록 확인 (공용 Study Log 링크)
```

```text
⚠️ 날짜는 MMDD, 시각은 HHmm의 숫자 네 자리로 입력해주세요.
예: /st 1013 1900 2030 Java 복습
```

```text
⚠️ MM 계정에 연결된 Members 이름이 없습니다. /st 내정보로 사용자 ID를 확인하고 관리자에게 계정 연결을 요청해주세요.
```

MM 저장 성공 링크는 개별 기록 대신 공용 Study Log 전체 로그를 엽니다. 저장 전 데이터 원본 조회에서 부모 데이터베이스 ID를 사용하므로 추가 환경변수는 필요 없습니다. Discord 저장 성공 링크와 겹침 실패 링크는 해당 개별 기록을 엽니다. `help`, `whoami`도 호환하지만 사용자에게는 `/st 도움말`, `/st 내정보`를 안내합니다.

## 실행·배포

직접 만든 Discord 봇으로 모든 일반 음성방의 체류 시간을 `모각코`로 자동 기록합니다. 토큰·서버 ID·사용자 매핑 설정은 [Discord 설정 안내](DISCORD_SETUP.md)를 참고합니다. 봇 토큰이 비어 있으면 기존 Mattermost만 실행됩니다. Discord는 3분 이상, 2026년 내 최대 24시간 구간을 시작일 기준 한 행으로 저장하고 개인 DM을 보냅니다. 1차 테스트는 메모리 상태만 사용하므로 앱 재시작 때 진행 중 기록은 유실됩니다.

Discord 수동 기록은 서버에 자동 등록되는 `/st`의 `입력` 옵션에 MM과 같은 내용을 넣습니다. 예: `/st 입력:1013 1900 2030 Java 복습`. `도움말`·`내정보`도 같은 입력란에 넣으며, 입력 생략은 도움말입니다. 수동 기록은 MM처럼 1~1440분을 허용하고 파서·겹침 검사·저장 기능을 공유합니다. 모든 응답은 실행자에게만 표시하며 수동 결과는 DM 대신 해당 비공개 응답에 표시됩니다.

[로컬 안내](LOCAL_TEST.md), [Render 안내](RENDER_DEPLOY.md), [Discord 인수인계](DISCORD_HANDOFF.md)를 참고합니다. MM 설정에서 Trigger Word를 **st**로 직접 변경합니다. Request Method POST와 URL `/api/mattermost/study`는 유지합니다. 이 저장소의 수정만으로 MM 설정이 바뀌지는 않습니다.

Mattermost의 표준 커스텀 슬래시 명령어는 영문·숫자와 일부 기호만 허용하므로 한글 이름 대신 사용자가 선택한 `/st`를 사용합니다. 도움말·내정보 같은 명령어 인자는 한글을 사용할 수 있습니다. [공식 Trigger 검증 코드](https://github.com/mattermost/mattermost/blob/master/server/public/model/command.go)

시스템 Java 8과 JAVA_HOME/PATH는 유지하고 STS 실행 JRE·Gradle JVM만 Java 21로 설정합니다.

```powershell
.\gradlew.bat test bootJar --no-daemon
```

Docker는 테스트·bootJar 빌드 후 Java 21 prod 프로필로 `build/libs/app.jar`를 실행합니다. Health는 `/actuator/health`이며 Notion 권한이나 저장 성공을 검증하지 않습니다. 로컬 설정·비밀 파일·테스트 HTML은 배포 JAR에서 제외합니다.

## 운영 한계와 진단

MM response_url이 있으면 입력 검증 후 처리 중 안내를 보내고 백그라운드에서 멤버 조회 → 속성 조회 → 겹침 조회 → 기록 생성 후 결과를 보냅니다. 로컬 화면처럼 response_url이 없으면 최종 결과를 직접 반환합니다. callback에 Notion 토큰을 붙이지 않으며 추가 MM 봇 토큰은 필요 없습니다.

Notion 연결 제한은 5초, 응답 제한은 8초입니다. 작업 스레드는 2~4개, 대기 한도는 50개입니다. 대기 작업은 메모리에 있어 강제 종료·재배포 때 유실될 수 있습니다. 겹침 조회와 저장은 별도 요청이라 동시 입력의 중복을 완전히 막지 못합니다. 불확실한 저장 결과를 무조건 재시도하지 않습니다.

MM callback 연결 제한은 3초, 응답 제한은 5초입니다. 결과 메시지 실패와 Notion 저장 실패를 구분하며 결과가 없으면 Notion을 먼저 확인합니다. callback 302 리다이렉트를 실패로 감지하지 못하는 문제는 별도 보완 대상으로 남아 있습니다.

Render Free는 요청이 15분간 없으면 절전할 수 있습니다. 초기 기동 지연은 비동기 처리로 해결되지 않습니다. 테스트 전 health UP을 확인합니다. Maven Central 다운로드 429와 Notion API 429는 별개입니다.

로그의 stage=members-query, study-schema, overlap-query, study-save와 예외 타입으로 확인합니다. Mattermost final response delivery failed는 메시지 전달 실패입니다. 토큰과 callback URL은 로그·커밋에 적지 않습니다.
