# STS 실행 후 Mattermost에서 실제 Notion 저장 확인

STS는 Run As → Spring Boot App으로 Cl15botApplication을 실행합니다. 외부 Tomcat에 올리는 Run on Server는 사용하지 않습니다. 시스템 Java 8/JAVA_HOME/PATH는 그대로 유지합니다.

## 1. Notion 준비

StudyPool 연결이 Study Log와 Members를 모두 읽을 수 있고 Study Log에 콘텐츠를 삽입할 수 있어야 합니다.
새 컬럼을 만들지 않습니다. Members의 기존 멤버 페이지 제목(예: 정명남)을 정확히 입력하면 봇이 제목 속성으로 검색합니다. 기존 이름/관계/수식/롤업은 변경하지 않습니다. 같은 이름이 여러 행에 있으면 저장하지 않습니다. 명령에 적은 이름으로 대상을 정하며 Mattermost 계정과 본인 여부를 대조하지 않습니다.

## 2. STS 설정

프로젝트 F5 → 필요시 Gradle → Refresh Gradle Project. Properties → Resource → Location이 현재 수정한 프로젝트 폴더인지 확인합니다.
Run Configurations → Spring Boot App → Cl15botApplication → JRE에서 Java 21, Gradle JVM도 Java 21로 설정합니다.
Environment에 아래 **다섯 개**를 각각 추가합니다. 시스템 환경변수가 아닌 이 실행 설정에만 등록합니다.

| 이름 | 값 |
|---|---|
| MATTERMOST_SLASH_TOKEN | 실제 /study 명령의 Token |
| MATTERMOST_ALLOWED_CHANNEL_ID | `by1prkd3yty7mybdssneo4uzpw` |
| NOTION_TOKEN | StudyPool의 API 토큰 |
| NOTION_DATA_SOURCE_ID | Study Log 데이터 원본 ID |
| NOTION_MEMBERS_DATA_SOURCE_ID | Members 데이터 원본 ID |

기존 NOTION_MEMBER_MAPPINGS는 삭제합니다. 제목 속성은 기본 `스터디`입니다. 이전 NOTION_TITLE_PROPERTY=Name이 남아 있으면 제거합니다.
Arguments의 local 프로필은 직접 MM 테스트에는 필요 없습니다. 이전 `--spring.profiles.active=local` 또는 Profile=local은 지워도 됩니다. 실제 키를 직접 적은 application-local.yaml 대신 Environment 설정을 사용하세요. 다른 bot 값을 Program arguments로 지정했다면 제거합니다.

Run As → Spring Boot App으로 실행합니다. 브라우저에서 http://127.0.0.1:8080/actuator/health 를 열어 `{"status":"UP"}`를 확인합니다. 이 응답은 서버 구동 확인이며 Notion 권한 검사는 아닙니다.

### Environment 탭이 안 보일 때

Run → Run Configurations에서 왼쪽 **Spring Boot App 아래 실제 실행 항목**을 선택합니다. 유형 제목만 선택한 상태와 구분합니다. 없다면 Cl15botApplication.java 우클릭 → Run As → Spring Boot App을 한 번 실행한 뒤 다시 확인합니다. 오른쪽 탭의 Environment가 창 폭 때문에 가려졌는지도 확인합니다.
Spring Boot App 항목이 없다면 Java Application → New → Project=cl15bot, Main class=com.example.cl15bot.Cl15botApplication으로 만들고 JRE를 Java 21로 선택해도 됩니다. Java Application 실행 설정은 Environment 탭을 제공합니다.

탭 설정 대신 Git 제외된 `src/main/resources/application-local.yaml`에 다섯 값을 넣어도 됩니다. 기존 파일의 bot 부분만 교체하고 bot 키를 두 번 만들지 마세요.

```yaml
bot:
  mattermost-token: "실제 Mattermost 슬래시 명령 Token"
  mattermost-allowed-channel-id: "by1prkd3yty7mybdssneo4uzpw"
  notion-token: "실제 StudyPool API 토큰"
  notion-data-source-id: "Study Log 데이터 원본 ID"
  notion-members-data-source-id: "Members 데이터 원본 ID"
  notion-title-property: "스터디"
```

이 파일 방식을 쓰면 Arguments → Program arguments에 `--spring.profiles.active=local`을 넣습니다. Profile 입력란이 있는 경우 `local`로 지정해도 됩니다. STS F5 후 서버를 재시작합니다. 이 설정 방식으로 공개 MM 테스트를 할 때도 토큰 인증은 그대로 적용되며 로컬 테스트 화면도 함께 열립니다. 키는 채팅이나 GitHub에 올리지 않습니다.
## 3. 임시 HTTPS 주소 만들기

Mattermost의 요청은 Mattermost 서버에서 전송되므로 Request URL에 네 PC의 localhost를 넣으면 안 됩니다. PC에서 STS 서버와 터널을 동시에 실행합니다.
프로젝트 폴더의 PowerShell에서:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-mm-tunnel.ps1
```

스크립트는 cloudflared가 있으면 사용하고, 없으면 공식 GitHub 릴리스에서 Windows x64 실행 파일을 Git 제외된 .local 폴더에 내려받습니다. 전역 설치나 PATH 수정은 하지 않습니다. 인터넷 연결이 필요합니다.
출력의 `https://…trycloudflare.com` 주소를 복사합니다. 터널 창은 계속 켜 두고, 종료는 Ctrl+C입니다. 재실행하면 주소가 바뀌므로 MM Request URL도 바꿔야 합니다.
8081 등 다른 포트를 쓴다면 `-Port 8081`을 추가합니다. Cloudflare 계정과 도메인은 필요 없습니다. 테스트용 서비스로 영구 운영 주소는 아닙니다.

## 4. Mattermost 명령 연결

Mattermost → Integrations → Slash Commands → 기존 study 명령 편집:

- Command Trigger Word: `study`
- Request Method: `POST`
- Request URL: `https://터널주소.trycloudflare.com/api/mattermost/study`
- Token: STS의 MATTERMOST_SLASH_TOKEN과 같은 값

토큰은 URL에 넣지 않습니다. 브라우저에서 터널주소 뒤 `/actuator/health`를 붙여 외부 연결을 확인합니다. 메뉴 수정 권한이 없으면 명령 소유자/관리자가 URL을 수정해야 합니다.

## 5. 실제 테스트 순서

1. 채널 ID `by1prkd3yty7mybdssneo4uzpw`에서 `/study help` → 사용법 응답 확인.
2. Notion Members의 기존 멤버 페이지 이름 확인 (추가 속성/ID 등록 없음).
3. `/study 2026-10-12 19:00 20:30 정명남 | MM 연결 테스트`에서 날짜·시각을 실제 과거 공부 시간으로, 정명남을 실제 기존 멤버 이름으로 바꿉니다.
4. 명령 입력 → 저장 성공 응답 확인.
5. 공용 Study Log에서 제목, 날짜, 이름 관계, 시작/종료 시간을 확인합니다. 해당 멤버 페이지의 필터 보기와 집계도 확인합니다.

다른 사용자도 자신의 기존 Members 이름을 명령에 넣으면 됩니다. 개별 페이지 ID나 Mattermost 사용자 ID를 등록하지 않습니다. 이름과 공부 내용 사이의 `|`는 필수이며 공부 내용의 공백을 그대로 유지합니다.
저장은 실제 공부 기록에 합산됩니다. 같은 날은 `/study 2026-10-12 19:00 20:30 정명남 | Java 복습`을 사용합니다. 자정을 넘기면 `/study 2026-10-12 23:00 2026-10-13 01:00 정명남 | Java 복습`처럼 종료 날짜도 입력합니다. 같은 날에도 종료 날짜를 적을 수 있습니다. 종료 날짜 생략 시 시작일과 같은 날짜로만 처리합니다. 종료가 시작보다 빠르면 거절하고 다음 날로 자동 처리하지 않습니다. 종료 일시가 시작보다 늦고 현재보다 미래가 아니어야 하며, 공부 시간은 1~1440분입니다. 잘못된 날짜/시각과 분만 입력한 명령도 거절합니다. 실패 응답은 `⚠️`로 시작하고, 입력 누락·날짜/시각 형식 오류·존재하지 않는 날짜/시각에만 둘째 줄 예시를 표시합니다.

같은 멤버의 기존 기록과 시간이 겹치면 내용과 관계없이 저장하지 않습니다. 같은 기록의 재입력, 부분 겹침, 포함 관계를 거절하며 끝점만 맞닿는 시간과 다른 멤버는 허용합니다. 자정을 넘긴 기록도 날짜를 포함해 비교합니다. 시간 초과 시 먼저 Notion 저장 여부를 확인합니다. 겹침 조회와 생성 사이에 동시에 들어온 요청은 중복을 완전히 막을 수 없습니다.

STS Environment에 `MATTERMOST_ALLOWED_CHANNEL_ID=by1prkd3yty7mybdssneo4uzpw`를 필수로 설정합니다. 기본값이 없으므로 미설정·빈 값이면 모든 채널에서 실행을 거절합니다. 로컬 화면에서도 채널 ID를 동일하게 입력합니다. 시작 날짜·시작 시각·종료 시각과 선택 종료 날짜 입력란을 이용해 테스트할 수 있습니다. 자정을 넘기면 종료 날짜 입력란도 채웁니다. 다른 채널과 빈 채널은 Notion 조회 전에 거절합니다.

## 문제 확인

| 증상 | 확인 |
|---|---|
| MM 응답 없음/접속 오류 | STS 실행, 터널 실행, 새 HTTPS URL, MM 서버의 외부 접속 허용 |
| HTTP 401 | MM 명령 Token과 STS 변수 일치 |
| 회원 미등록 | Members 데이터 원본 ID, 입력한 이름과 기존 제목의 정확한 일치 |
| 회원 중복 | 같은 이름의 Members 행을 관리자가 확인하기 |
| Notion 400 | Members의 기존 제목, Study Log 속성 이름/타입 |
| Notion 401/403/404 | API 토큰, 두 데이터 원본 ID, 연결의 두 데이터베이스 접근 권한 |
| 서버 미설정 안내 | STS 실행 설정의 네 환경변수 확인 후 재시작 |

브라우저 보조 화면이 필요할 때만 local 프로필을 켜고 `/local-test`를 사용합니다. 화면에도 실제 MM Token과 기존 Members 이름을 입력합니다. 설정 확인은 값 유무만 보여주고 회원을 API 조회하지 않습니다. 공개 MM 테스트는 local 프로필을 끄는 것을 권장합니다.

공식 안내: [Mattermost 명령 설정](https://docs.mattermost.com/developers/integrate/slash-commands/custom), [Cloudflare 임시 터널](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [Notion 데이터 원본 조회](https://developers.notion.com/reference/query-a-data-source).
## 배포 JAR 안내

배포 JAR은 build/libs/app.jar이며 개인 설정과 로컬 HTML을 제외합니다. 로컬 보조 화면은 STS Spring Boot App 또는 bootRun으로 실행하세요. Render 배포는 [RENDER_DEPLOY.md](RENDER_DEPLOY.md)를 따릅니다.
