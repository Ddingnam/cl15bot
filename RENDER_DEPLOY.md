# Render 배포와 Mattermost 실제 저장 테스트

## 1. 배포 전

최신 프로젝트 파일을 GitHub Ddingnam/cl15bot의 master에 push합니다. 로컬 application-local.yaml이나 토큰을 커밋하지 않습니다. 네 환경변수를 설정합니다. 새 Notion 컬럼을 만들지 않습니다.

## 2. Render 생성

아직 서비스를 만들지 않았다면 Render → **New → Blueprint** → GitHub 연결 → **Ddingnam/cl15bot** → Branch **master**를 선택합니다. `render.yaml`에서 cl15bot Web Service 설정을 읽습니다. **Free인지 확인**한 후 네 값의 입력 칸에 아래 값을 넣습니다.

| 변수 | 값 |
|---|---|
| MATTERMOST_SLASH_TOKEN | 기존 /study 슬래시 명령 Token |
| NOTION_TOKEN | StudyPool 연결 API 토큰 |
| NOTION_DATA_SOURCE_ID | Study Log 데이터 원본 ID |
| NOTION_MEMBERS_DATA_SOURCE_ID | Members 데이터 원본 ID |

Blueprint를 생성하여 배포합니다. `sync: false` 값은 최초 생성 때 입력하고, 나중에 수정할 때는 해당 서비스의 Environment에서 변경합니다.

이미 서비스를 생성했다면 중복 생성하지 말고 기존 서비스를 아래 설정과 맞춥니다. New → Web Service로 직접 만드는 경우에도 동일합니다.

| 설정 | 값 |
|---|---|
| Repository | Ddingnam/cl15bot (GitHub 계정 연결 방식) |
| Branch | master |
| Language / Runtime | Docker |
| Dockerfile Path | ./Dockerfile |
| Docker Build Context | . |
| Region | Singapore |
| Instance Type | Free |
| Health Check Path | /actuator/health |
| Auto-Deploy | On Commit |

Build Command, Start Command, Docker Command는 별도로 덮어쓰지 않습니다. Dockerfile이 빌드와 실행을 정의합니다. 컨테이너는 prod 프로필로 실행합니다. 네 변수는 Render Environment에서 입력합니다. `NOTION_MEMBER_MAPPINGS`, `SPRING_PROFILES_ACTIVE=local`, 직접 지정한 `PORT`는 등록하지 않습니다. Notion에 저장하므로 Render Postgres나 디스크를 만들 필요가 없습니다.

결제 수단 없이 Free로 테스트합니다. 월별 무료 인스턴스 시간/대역폭/빌드 한도에 도달하면 서비스나 빌드가 제한될 수 있습니다. 유료 플랜으로 자동 변경하는 설정은 없습니다.

## 3. 실행 상태 확인

배포 로그에서 테스트, bootJar, 컨테이너 실행과 Started Cl15botApplication을 확인합니다. 서비스가 Live가 되면 Render가 발급한 HTTPS 주소에서:

```text
https://실제서비스주소.onrender.com/actuator/health
```

`{"status":"UP"}`인지 확인합니다. Notion 권한까지 확인하는 경로는 아닙니다. `/local-test`와 `/local-test/status`는 배포 환경에서 404여야 합니다.

## 4. Mattermost Request URL 수정

Integrations → Slash Commands → 기존 study 명령 편집:

```text
Command Trigger Word: study
Request Method: POST
Request URL: https://실제서비스주소.onrender.com/api/mattermost/study
Autocomplete Hint: <시작일 YYYY-MM-DD> <시작 HH:mm> [종료일 YYYY-MM-DD] <종료 HH:mm> <Members 이름> | <공부 내용>
```

명령의 Token은 Render의 MATTERMOST_SLASH_TOKEN과 같아야 합니다. URL에 토큰을 붙이지 않습니다. 로컬 STS 서버나 외부 터널을 실행할 필요가 없습니다.

자동완성 설명에는 `한국 시간으로 입력 · 자정을 넘기면 종료 날짜 필수 · /study help`를 넣습니다. 같은 날은 `/study YYYY-MM-DD HH:mm HH:mm 이름 | 내용`, 다른 날에 끝나면 `/study YYYY-MM-DD HH:mm YYYY-MM-DD HH:mm 이름 | 내용`을 사용합니다. 대괄호는 선택 항목 표시이며 실제 명령에 입력하지 않습니다. 종료 날짜를 생략하면 시작일과 같은 날짜로 처리합니다. 실패 응답은 `⚠️`로 시작하며, 입력 누락·날짜/시각 형식 오류·존재하지 않는 날짜/시각에만 둘째 줄 예시를 표시합니다. 그 밖의 실패는 이유와 사용자 행동만 표시합니다.

## 5. 실제 Notion 저장

StudyPool 연결이 Study Log와 Members에 접근할 수 있는지 확인합니다. 콘텐츠 읽기와 삽입 권한이 필요합니다.

모든 채널과 DM에서 실행할 수 있습니다. 이전 MATTERMOST_ALLOWED_CHANNEL_ID 환경변수는 사용하지 않으므로 Render Environment에서 삭제해도 됩니다.

```text
/study help
/study 2026-10-12 19:00 20:30 정명남 | 시간 지정 테스트
```

정명남을 기존 Members의 실제 페이지 이름으로 바꿉니다. 이름과 내용 사이 `|`를 포함합니다. 공용 Study Log에서 스터디/날짜/이름 관계/시작 시간/종료 시간과 멤버별 필터 보기 및 합계를 확인합니다. 이름이 없거나 중복이면 봇이 저장을 막습니다.
이름을 직접 지정하는 방식이므로 요청자의 MM 계정과 멤버의 본인 여부를 대조하지 않습니다. 시험 기록도 실제 집계에 포함됩니다.

정상 입력은 먼저 “처리 중” 안내가 나오고, 이후 비공개 최종 결과가 도착합니다. 성공 응답의 Notion 링크를 눌러 바로 확인합니다. 시간 겹침 실패에는 기존 기록의 시간대와 링크가 표시됩니다. 입력 형식 오류와 도움말은 즉시 응답합니다. 새 환경변수나 별도 Mattermost 봇 토큰은 필요 없습니다. Mattermost가 제공하는 response_url이 Render에서 접근 가능한 HTTP(S) 주소여야 합니다.

최종 결과가 오지 않으면 재입력 전에 Notion 저장 여부를 확인합니다. Render Logs의 Mattermost final response delivery failed는 결과 메시지 전송 실패이며 Notion 저장 실패를 뜻하지 않습니다. 콜백 URL에는 인증 정보가 있으므로 공유하지 않습니다. 재배포나 서버 강제 종료 중인 작업은 완료되지 않을 수 있습니다. Render Free의 초기 서버 기동 지연은 비동기 처리로 없어지지 않으므로 기존처럼 health를 확인한 뒤 테스트합니다.

예시는 종료 시각이 현재보다 과거일 때 사용합니다. 같은 멤버의 동일 명령, 다른 내용의 동일 시간, 일부 겹치는 시간은 겹침 안내가 나오고 행은 늘어나지 않아야 합니다. 끝점만 맞닿는 시간과 다른 멤버는 허용됩니다. 자정을 넘긴 기록은 `/study 2026-10-12 23:00 2026-10-13 01:00 정명남 | Java 복습`으로 테스트합니다. 종료 날짜 없이 `23:00 01:00`을 입력하면 거절되어야 합니다. 잘못된 날짜·시작보다 빠르거나 같은 종료 일시·1440분 초과·미래 종료·분만 입력한 명령도 거절되는지 확인합니다. 다른 채널과 DM에서도 정상 저장되는지 확인합니다. 동시에 들어온 요청의 중복을 완전히 보장하지는 않습니다.

## 6. 이후 코드 변경

연결한 master에 push하면 On Commit 설정으로 재배포됩니다. 공개 저장소 URL만 붙이는 방식은 자동 배포가 지원되지 않으므로 GitHub 계정으로 저장소를 연결합니다. 재배포에 로컬 설정은 전달되지 않으며, 실제 키는 Render Environment에서 계속 관리합니다.

무료 서버는 15분 동안 요청이 없으면 잠듭니다. MM의 첫 명령이 타임아웃될 수 있으므로 health 주소를 열어 UP을 확인한 뒤 테스트합니다. 자동으로 서버를 계속 깨우는 작업은 구성하지 않습니다. MM에서 타임아웃되었을 경우에도 먼저 Notion에 기록이 생성됐는지 확인합니다.

| 증상 | 확인 |
|---|---|
| 빌드 실패 | Render Logs에서 첫 오류, 저장소 최신 코드와 Dockerfile 경로 |
| 시작/포트 감지 실패 | Docker Command 덮어쓰기 제거, prod 실행, PORT 직접 설정 제거 |
| 401 | MM 명령 Token과 Render 변수 일치 |
| Notion 설정 누락 안내 | 네 환경변수 이름과 값, 변경 후 재배포 완료 여부 |
| 이름 없음/중복 | Members 기존 이름의 정확한 일치, 중복 행 |
| 처리 중 이후 최종 응답 없음 | Notion 저장 여부 먼저 확인, Mattermost final response delivery failed 로그와 response_url의 Render 접근 가능 여부 |
| Notion 400 | Study Log의 다섯 속성 이름·타입 |
| Notion 401/403/404 | 토큰과 두 데이터 원본 ID, StudyPool의 두 데이터베이스 권한 |
| 시간 겹침 안내 | 같은 멤버의 기존 Study Log 시작/종료 시간과 새 구간의 교집합 확인 |
| 속성/겹침 조회 실패 | Notion preflight failure의 stage=study-schema 또는 stage=overlap-query와 연결·읽기 권한 (저장 요청 없음) |

공식 문서: [Blueprint](https://render.com/docs/blueprint-spec), [Docker](https://render.com/docs/docker), [자동 배포](https://render.com/docs/deploys), [Free 제한](https://render.com/docs/free).
## Notion 통신 오류 로그 확인

Render의 서비스 Logs에서 Notion network failure를 검색합니다. 이 오류는 앱이 처리하므로 배포 상태가 Failed로 바뀌지 않으며 WARN 수준으로 표시됩니다. stage=members-query는 Members 조회 중 실패하여 저장 요청을 보내지 않은 경우이고, stage=study-save는 기록 저장 중 실패한 경우입니다. causes의 HttpConnectTimeoutException은 연결 제한, HttpTimeoutException은 요청 제한, SSLException은 TLS, UnknownHostException은 DNS 문제를 조사할 단서입니다. 실제 토큰이나 Notion 응답 본문은 기록하지 않습니다.

배포 후 시작 로그의 Notion client: connectTimeout=5s requestTimeout=8s ipv4Only=true가 새 네트워크 설정 적용 확인 기준입니다. Java의 IPv4 소켓 설정과 5초 연결 제한은 경로 문제/지연을 확인하기 위한 조치입니다. 계속 HttpConnectTimeoutException이면 Render 발신 경로나 Notion 측 접속 문제를 추가로 확인해야 하며, 토큰을 반복 교체할 필요는 없습니다. Java 설정 근거: https://docs.oracle.com/en/java/javase/21/core/java-networking.html
