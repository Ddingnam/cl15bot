# cl15bot — 기존 Notion Study Log 연동 MVP

Java 21 + Spring Boot 4.1.1 + Gradle 9.7.1. Mattermost `/study 60 Java 복습`을 공통 Study Log에 저장하고 `이름` 관계를 Members의 회원 페이지에 연결합니다. 회원 페이지의 연결된 보기는 기존 회원 필터에 따라 기록을 표시합니다.

## 명령과 기록 시간

```text
/study 60 Java 스트림 복습
/study 30 알고리즘 BFS 문제 풀이
/study whoami
/study help
```

분은 정수 1~1440, 공부 내용은 필수이며 전체 입력은 2000자 이내입니다. 응답은 실행한 사용자에게만 보입니다.

`/study 60 Java 복습`은 **입력 시점을 종료 시간, 60분 전을 시작 시간**으로 기록합니다. 한국 시간(Asia/Seoul)으로 분 단위까지 기록하고 `날짜`에는 시작일을 넣습니다. 예를 들어 10월 12일 00:30에 60분을 기록하면 시작은 10월 11일 23:30, 종료는 10월 12일 00:30, 날짜는 10월 11일입니다. 과거의 날짜·시각을 직접 지정하는 명령은 아직 없습니다.

## 기존 Notion 속성

새 데이터베이스나 열을 만들 필요가 없습니다. 제공된 Study Log 구조에 맞춰 아래 다섯 속성만 입력합니다.

| 속성 이름 | 타입 | 봇이 입력하는 값 |
|---|---|---|
| 스터디 | 제목(Title) | 공부 내용 |
| 이름 | 관계(Relation → Members) | 명령을 입력한 회원의 Members 페이지 ID |
| 날짜 | 날짜(Date) | 공부 시작일 |
| 시작 시간 | 날짜(Date, 시간 포함) | 입력 시점에서 공부 시간을 뺀 시각 |
| 종료 시간 | 날짜(Date, 시간 포함) | 입력 시점 |

`총 공부 시간`, `총 공부 시간 (분)`, `시즌 학습 시간 (분)`과 Members의 집계, 랭킹은 기존 수식·롤업에 맡깁니다. 봇은 계산 속성을 쓰거나 수정하지 않습니다. `텍스트` 속성도 쓰지 않습니다. 실제 계산 결과는 기존 Notion 수식에 따라 결정됩니다.

공통 Study Log 데이터 소스 ID를 하나만 사용합니다. 회원마다 다른 Study Log ID를 등록하는 구조가 아닙니다. 회원 구분은 Mattermost 사용자 ID와 **Members의 회원 페이지 ID**를 연결해서 처리합니다. 이름이 같거나 바뀌어도 사용자 ID로 구분하며 등록되지 않은 사용자는 저장하지 않습니다.

## 설정 순서

### 1. Notion Integration 생성 및 권한 연결

[Notion integrations](https://www.notion.so/profile/integrations)에서 해당 워크스페이스의 내부 Integration을 만들고 Read content, Insert content 권한을 활성화합니다. Internal Integration Secret을 복사해 `NOTION_TOKEN`으로 사용합니다.

공통 **Study Log와 Members 데이터베이스 모두** Connections/연결 메뉴에서 이 Integration에 접근 권한을 줍니다. 관계 속성에 연결할 Members도 접근 가능해야 합니다. 토큰 생성만으로는 권한이 생기지 않습니다.

### 2. 공통 Study Log 데이터 소스 ID 복사

회원 페이지 안 표의 `↗ Study Log`를 눌러 원본 데이터베이스를 엽니다. 원본의 설정 → Manage data sources/데이터 소스 관리 → 해당 소스의 `…` → Copy data source ID를 선택합니다. 이 값이 `NOTION_DATA_SOURCE_ID`입니다.

Members 데이터베이스 ID, 관리자용 페이지 ID, URL의 보기 ID와 구분하세요. API는 `Notion-Version: 2025-09-03`, `POST /v1/pages`의 `parent.data_source_id`를 사용합니다. [Notion 공식 업그레이드 안내](https://developers.notion.com/guides/get-started/upgrade-guide-2025-09-03).

### 3. Mattermost 슬래시 명령 생성

Mattermost → Integrations/통합 → Slash Commands → Add:

| 항목 | 값 |
|---|---|
| Display Name | 공부 기록 |
| Command Trigger Word | study (슬래시 제외) |
| Request Method | POST |
| Request URL | 배포 후 `https://<Cloud Run 서비스 주소>/api/mattermost/study` |
| Autocomplete Hint | `<분> <공부 내용>` |

배포 URL이 아직 없으면 임시 `https://example.com/api/mattermost/study`로 만들고 배포 후 교체합니다. 생성 후 표시되는 **슬래시 명령 Token**이 `MATTERMOST_SLASH_TOKEN`입니다. 개인 액세스 토큰이나 Incoming Webhook URL은 필요하지 않습니다. [Mattermost 공식 설정 안내](https://docs.mattermost.com/developers/integrate/slash-commands/custom).

### 4. 세 기본 값 입력 후 서버 실행/배포

| 환경변수 | 값 |
|---|---|
| MATTERMOST_SLASH_TOKEN | 슬래시 명령 Token |
| NOTION_TOKEN | Notion Integration Secret |
| NOTION_DATA_SOURCE_ID | 공통 Study Log의 데이터 소스 ID |

STS에서는 Run Configurations → Spring Boot App → Cl15botApplication → Environment 탭에 입력합니다. 실행 JRE와 Gradle JVM은 Java 21로 지정합니다. 프로젝트의 기존 Buildship 설정에는 JDK 25 경로가 저장되어 있으니 별도로 확인하세요. 시스템 Java 8과 PATH/JAVA_HOME은 변경할 필요가 없습니다.

`.env.example`은 참고용이며 `.env` 파일은 자동 로딩하지 않습니다. 토큰은 소스 코드나 채팅에 넣지 않습니다.

### 5. 회원 연결 등록

서비스 URL을 Mattermost 명령의 Request URL로 설정한 뒤 본인이 `/study whoami`를 입력합니다. Notion 토큰이나 회원 연결이 없어도 슬래시 토큰이 올바르면 사용자 ID를 확인할 수 있습니다.

Notion **Members에서 본인 페이지**를 열어 링크를 복사합니다. 링크 경로 끝의 32자리 ID가 회원 페이지 ID입니다. URL의 `?` 이후 쿼리는 제외합니다. 공부 기록 행의 페이지 ID가 아니라 `정명남` 등 **회원 자체 페이지의 ID**를 사용합니다.

그다음 아래 환경변수를 추가하고 서버를 재시작하거나 Cloud Run 새 버전을 배포합니다.

```text
NOTION_MEMBER_MAPPINGS=실제Mattermost사용자ID=실제Notion회원페이지ID
```

회원 여러 명은 쉼표로 구분합니다. 다음은 형식 예시이며 실제 ID로 바꿔야 합니다.

```text
NOTION_MEMBER_MAPPINGS=mmUserA=00000000-0000-0000-0000-000000000001,mmUserB=00000000-0000-0000-0000-000000000002
```

하이픈이 없는 32자리 Notion 페이지 ID도 가능합니다. 잘못된 형식이나 중복 Mattermost 사용자 ID는 서버 시작 시 설정 오류로 거부합니다. 회원 연결 설정은 관리자가 변경합니다. STS에 넣은 값이 Cloud Run으로 자동 전달되지는 않습니다.

### 6. 실제 저장 확인

```text
/study 1 연결 테스트
```

공통 Study Log에 제목·회원·시작/종료 시간이 들어가고 본인 Member 페이지의 보기에 표시되는지 확인합니다. 기존 수식에 따른 시간 합계와 시즌 집계도 확인합니다. 오늘 날짜와 기존 시즌 기준이 다르면 시즌 시간은 0일 수 있으므로 수식 규칙을 확인하세요.

## Cloud Run 배포

Dockerfile은 JDK 21에서 테스트와 JAR 빌드를 하고 JRE 21에서 실행합니다. 서버는 `0.0.0.0:${PORT:8080}`에서 수신합니다. [Cloud Run 컨테이너 요구사항](https://docs.cloud.google.com/run/docs/container-contract).

Google Cloud 프로젝트에서 Cloud Run, Cloud Build, Artifact Registry API를 활성화하고 gcloud 로그인과 프로젝트 선택 후:

```powershell
gcloud run deploy cl15bot --source . --region asia-northeast3 --allow-unauthenticated --memory 512Mi --min-instances 1 --max-instances 2
```

Cloud Run 콘솔 → 서비스 → 새 버전 수정 및 배포 → 변수 및 보안 비밀에서 세 기본 값과 `NOTION_MEMBER_MAPPINGS`를 등록합니다. 키 없이도 서버는 부팅하지만 저장되지 않습니다. `NOTION_TITLE_PROPERTY`는 선택 값이며 기본은 `스터디`입니다. 기존 `Name` 설정이 있으면 제거하거나 `스터디`로 바꿉니다. `PORT`는 Cloud Run이 자동 지정합니다.

토큰 두 개는 Secret Manager 참조로 등록하는 것을 권장합니다. 서비스 계정에 해당 Secret의 Secret Accessor 권한을 부여합니다. ID와 회원 연결표는 일반 환경변수로 등록할 수 있습니다.

Mattermost가 Google IAM 인증을 수행하지 않으므로 외부 HTTP 호출을 허용하고 앱의 슬래시 토큰으로 검증합니다. 최소 인스턴스 1은 콜드 스타트 지연을 줄이지만 유휴 비용이 발생합니다. 0이면 첫 요청이 늦어질 수 있습니다.

## 로컬 빌드와 HTTP 확인

검증용 JDK 21이 `.local/jdk21`에 있다면 환경변수 변경 없이:

```powershell
$taskJdk = (Get-ChildItem .local\jdk21 -Directory | Select-Object -First 1).FullName
& "$taskJdk\bin\java.exe" "-Dorg.gradle.java.home=$taskJdk" "-Dorg.gradle.java.installations.paths=$taskJdk" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain test bootJar --no-daemon
```

STS 실행 후 `http://localhost:8080/actuator/health`에서 `UP`을 확인합니다. 이 health는 서버 실행 상태이며 Notion 연결을 검증하지 않습니다. 로컬 사용자 ID 확인:

```powershell
$body = @{ token = '<슬래시 Token>'; text = 'whoami'; user_id = '<테스트 사용자 ID>' }
Invoke-RestMethod -Uri http://localhost:8080/api/mattermost/study -Method Post -ContentType 'application/x-www-form-urlencoded; charset=utf-8' -Body $body
```

실제 로컬 저장 요청에는 `text='60 Java 복습'`, `user_name`, `channel_id`도 보내고 `user_id`에 회원 연결표에 등록한 값을 사용합니다. Mattermost 서버에서 PC의 localhost로는 접근할 수 없으므로 실제 슬래시 테스트에는 배포 주소를 사용합니다.

## 오류와 MVP 범위

잘못된 슬래시 토큰은 HTTP 401, 토큰 미설정은 503입니다. 입력 오류·회원 미등록·Notion 오류는 ephemeral JSON으로 안내합니다. Notion 400은 속성 이름/타입, 401/403은 토큰/권한, 404는 데이터 소스 ID/관계 대상/접근 권한, 429는 요청 제한을 확인합니다. 로그에 원문 오류나 토큰은 남기지 않습니다.

Notion 연결 제한 1초, 응답 제한 3초이며 동기식으로 저장합니다. 타임아웃에는 이미 저장됐을 수 있으므로 재입력 전 표를 확인하세요. 자동 재시도나 영구 중복 방지는 없습니다. 동일 명령을 다시 입력하면 새 행이 생깁니다. 과거 시각 입력, 합계 조회, 기록 수정/삭제, 비동기 큐는 미구현입니다.

테스트는 외부 Notion 계정에 쓰지 않고 인증·입력·회원 분리·자정 경계·Notion 요청 속성과 오류 응답을 확인합니다. 실제 Notion 저장과 랭킹 반영은 사용자의 키와 접근 권한을 넣은 뒤 검증해야 합니다. Docker 이미지 빌드와 Cloud Run 배포는 아직 실행하지 않았습니다.
