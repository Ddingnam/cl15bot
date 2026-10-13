# cl15bot

Java 21 · Spring Boot 4.1.1 · Gradle 9.7.1 기반 Mattermost → Notion 공부 기록 봇입니다. Render Free Web Service에 Docker로 배포합니다.

```text
/study 60 정명남 | Java 스트림 복습
/study 30 정명남 | 알고리즘 BFS 문제 풀이
/study help
```

Members의 기존 페이지 이름을 정확히 조회하고 공용 Study Log의 `이름` 관계에 연결합니다. 새 Notion 컬럼이나 Mattermost 사용자 ID 등록은 필요 없습니다. 같은 이름이 없거나 중복이면 저장하지 않습니다. 입력한 이름으로 대상을 정하며 Mattermost 계정과 해당 멤버의 본인 여부를 대조하지 않습니다.

## Render 배포

[배포 및 MM 테스트 안내](RENDER_DEPLOY.md)를 따릅니다. 루트의 `render.yaml`은 **Free**, **Singapore**, **master**, **Docker**, **On Commit 자동 배포**, **/actuator/health**를 지정합니다.

GitHub에 최신 코드를 올린 뒤 Render의 New → Blueprint에서 저장소와 master 브랜치를 선택하면 설정을 가져옵니다. 일반 New → Web Service로 직접 생성하는 방법도 배포 안내에 있습니다. 이미 Web Service를 만들었다면 같은 서비스를 수정하여 중복 생성하지 않습니다.

| 필수 환경변수 | 값 |
|---|---|
| MATTERMOST_SLASH_TOKEN | Mattermost /study 명령 Token |
| NOTION_TOKEN | StudyPool 연결의 API 토큰 |
| NOTION_DATA_SOURCE_ID | 공용 Study Log 데이터 원본 ID |
| NOTION_MEMBERS_DATA_SOURCE_ID | Members 데이터 원본 ID |

실제 값은 Render에 입력합니다. `render.yaml`에는 네 변수 모두 `sync: false`로 선언하고 키를 저장하지 않습니다. `.env.example`은 참고용이며 Spring Boot가 `.env` 파일을 자동으로 읽지는 않습니다. 제목 속성 이름을 바꾼 경우에만 선택 변수 `NOTION_TITLE_PROPERTY`를 설정합니다. 기본은 `스터디`입니다.

## 기존 Notion 구조

StudyPool 연결에 Study Log와 Members 접근 권한과 콘텐츠 읽기/삽입 권한을 부여합니다. Members는 기존 제목 속성(ID `title`)으로 조회합니다. Study Log에 입력하는 속성은 아래 다섯 개입니다.

| 속성 이름 | 타입 | 입력 값 |
|---|---|---|
| 스터디 | 제목 | 공부 내용 |
| 이름 | 관계 → Members | 조회한 멤버 페이지 |
| 날짜 | 날짜 | 공부 시작일 |
| 시작 시간 | 날짜·시간 | 입력 시점에서 공부 시간을 뺀 시각 |
| 종료 시간 | 날짜·시간 | 입력 시점 |

총 공부 시간, 시즌 시간, Members 롤업과 랭킹은 기존 Notion 수식을 사용합니다. 봇은 계산 속성을 수정하지 않습니다. 멤버 페이지의 기존 필터 보기에 해당 기록이 표시됩니다.

분은 정수 1~1440이고 이름·공부 내용은 필수입니다. 전체 입력은 2000자 이내입니다. 한국 시간(Asia/Seoul)으로 분 단위까지 기록하며 자정을 넘기면 시작일을 `날짜`에 넣습니다. `/study whoami`는 MM 사용자 ID 확인용이며 회원 연결에는 사용하지 않습니다. 응답은 실행한 사용자에게만 표시됩니다.

## 운영 실행과 로컬 실행

Dockerfile은 Java 21에서 테스트와 `bootJar` 빌드를 수행하고 JRE 21에서 비루트 사용자로 `app.jar`를 실행합니다. 컨테이너 실행 프로필은 `prod`, 수신 주소는 `0.0.0.0:${PORT:8080}`입니다. Render가 PORT를 제공하므로 직접 등록하지 않습니다. JVM 힙 상한은 컨테이너 메모리의 60%로 지정합니다.

개인 `application-local.*`, `application-secrets.*` 및 로컬 테스트 HTML은 배포 JAR에서 제외됩니다. 운영 프로필에서는 로컬 테스트 API도 비활성화됩니다. `.dockerignore`로 로컬 JDK·설정·환경 파일·로그·터널 스크립트를 빌드 컨텍스트에서 제외합니다.

STS에서는 [LOCAL_TEST.md](LOCAL_TEST.md)를 참고합니다. Run As → Spring Boot App으로 실행하고 실행 JRE와 Gradle JVM만 Java 21로 지정합니다. 시스템 Java 8/JAVA_HOME/PATH는 변경하지 않습니다. 로컬 화면은 STS의 `local` 프로필 또는 `bootRun`에서 사용하며 배포 JAR에는 포함하지 않습니다.

Java 21이 현재 터미널에 설정되어 있다면:

```powershell
.\gradlew.bat test bootJar --no-daemon
java -jar build/libs/app.jar --spring.profiles.active=prod
```

출력 JAR은 `build/libs/app.jar`입니다. 상태 확인 경로 `/actuator/health`는 앱 실행 상태를 보여주며 Notion 권한/저장 성공을 검증하지 않습니다.

## MVP 범위와 오류

슬래시 토큰이 다르면 HTTP 401, 미설정이면 503입니다. 입력 오류, 이름 미등록/중복, Notion 오류는 비공개 응답으로 안내합니다. Notion 400은 속성 타입, 401/403은 토큰/권한, 404는 두 데이터 원본 ID와 접근 권한을 확인합니다. 원문 API 오류와 토큰은 로그에 출력하지 않습니다.

Notion 연결 제한은 1초, 요청별 응답 제한은 3초이며 멤버 조회 후 동기식으로 저장합니다. 자동 재시도나 영구 중복 방지는 없습니다. 같은 명령을 다시 보내면 새 행이 생깁니다. 타임아웃이 발생하면 재입력 전에 Notion 저장 여부를 확인합니다. 과거 시각 입력, 기록 수정/삭제, 사용자 본인 인증, 비동기 큐는 아직 지원하지 않습니다.

Render Free는 15분 동안 요청이 없으면 잠들며 재시작에 약 1분이 걸립니다. MM 테스트 전에 health 주소에서 UP을 확인합니다. 무료 한도 내 테스트용으로 사용하고 실제 키로 저장 결과를 확인하세요. [무료 정책](https://render.com/docs/free)