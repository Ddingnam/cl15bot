# Render 배포와 Mattermost 실제 저장 테스트

## 1. 배포 전

최신 프로젝트 파일을 GitHub Ddingnam/cl15bot의 master에 push합니다. 로컬 application-local.yaml이나 토큰을 커밋하지 않습니다. 필요한 값은 이미 받은 네 환경변수뿐입니다. 새 Notion 컬럼을 만들지 않습니다.

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
Autocomplete Hint: <분> <Members 이름> | <공부 내용>
```

명령의 Token은 Render의 MATTERMOST_SLASH_TOKEN과 같아야 합니다. URL에 토큰을 붙이지 않습니다. 로컬 STS 서버나 외부 터널을 실행할 필요가 없습니다.

## 5. 실제 Notion 저장

StudyPool 연결이 Study Log와 Members에 접근할 수 있는지 확인합니다. 콘텐츠 읽기와 삽입 권한이 필요합니다.

```text
/study help
/study 1 정명남 | Render 연결 테스트
```

정명남을 기존 Members의 실제 페이지 이름으로 바꿉니다. 이름과 내용 사이 `|`를 포함합니다. 공용 Study Log에서 스터디/날짜/이름 관계/시작 시간/종료 시간과 멤버별 필터 보기 및 합계를 확인합니다. 이름이 없거나 중복이면 봇이 저장을 막습니다.
이름을 직접 지정하는 방식이므로 요청자의 MM 계정과 멤버의 본인 여부를 대조하지 않습니다. 시험 기록도 실제 집계에 포함됩니다.

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
| Notion 400 | Study Log의 다섯 속성 이름·타입 |
| Notion 401/403/404 | 토큰과 두 데이터 원본 ID, StudyPool의 두 데이터베이스 권한 |

공식 문서: [Blueprint](https://render.com/docs/blueprint-spec), [Docker](https://render.com/docs/docker), [자동 배포](https://render.com/docs/deploys), [Free 제한](https://render.com/docs/free).
## Notion 통신 오류 로그 확인

Render의 서비스 Logs에서 Notion network failure를 검색합니다. 이 오류는 앱이 처리하므로 배포 상태가 Failed로 바뀌지 않으며 WARN 수준으로 표시됩니다. stage=members-query는 Members 조회 중 실패하여 저장 요청을 보내지 않은 경우이고, stage=study-save는 기록 저장 중 실패한 경우입니다. causes의 HttpConnectTimeoutException은 연결 제한, HttpTimeoutException은 요청 제한, SSLException은 TLS, UnknownHostException은 DNS 문제를 조사할 단서입니다. 실제 토큰이나 Notion 응답 본문은 기록하지 않습니다.
