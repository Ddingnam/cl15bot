# STS에서 공부 기록 테스트

시스템 Java 8/JAVA_HOME/PATH는 유지합니다. STS 실행 JRE와 Gradle JVM만 Java 21로 지정합니다. Run As → Spring Boot App을 사용하며 외부 Tomcat의 Run on Server는 필요 없습니다.

## 설정

Run Configurations → 실제 Spring Boot App 실행 항목 → Environment에 다음을 등록합니다.

| 변수 | 값 |
|---|---|
| MATTERMOST_SLASH_TOKEN | MM 공부 명령 Token |
| MATTERMOST_MEMBER_MAPPINGS | `{"실제_MM_사용자_ID":"정명남"}` |
| NOTION_TOKEN | Integration API 토큰 |
| NOTION_DATA_SOURCE_ID | Study Log 원본 |
| NOTION_MEMBERS_DATA_SOURCE_ID | Members 원본 |

새 Notion 컬럼은 필요 없습니다. 실제 토큰은 Git에 넣지 않습니다. 이전 MATTERMOST_ALLOWED_CHANNEL_ID와 NOTION_MEMBER_MAPPINGS는 사용하지 않습니다.

설정 파일 방식은 `config/application-local.yaml.example`을 Git 제외된 `src/main/resources/application-local.yaml`로 복사합니다. 기존 개인 파일을 덮어쓰지 않도록 확인합니다. 계정 매칭은 환경변수 또는 YAML 문자열로 등록합니다.

```yaml
bot:
  mattermost-member-mappings: '{"local-test":"정명남"}'
```

local 프로필에서 `/local-test` 화면을 사용합니다. 테스트 사용자 ID는 매칭 JSON의 키와 같아야 합니다. 이름 입력란은 없고 서버가 연결된 이름을 결정합니다. 화면의 날짜·시각은 MMDD·HHmm로 변환되며 2026년만 지원합니다. response_url이 없어 최종 결과가 화면에 직접 표시됩니다. 도움말·설정 확인·내정보 확인은 기록을 만들지 않습니다.

## MM에서 시험

STS는 외부에서 바로 접근할 수 없으므로 MM에 연결하려면 외부 HTTPS 터널이 필요합니다. 기존 터널을 사용하거나 Render 배포 서버에서 확인합니다. 터널 재실행 시 주소가 바뀌면 MM URL도 바꿉니다. URL에 토큰을 넣지 않습니다.

MM Integrations → Slash Commands에서 Trigger Word `st`, Request Method `POST`, URL `<접근 가능한 주소>/api/mattermost/study`를 설정합니다.

```text
/st 도움말
/st 내정보
/st 1013 1900 2030 Java 복습
/st 1013 2300 1014 0100 Java 복습
```

예시는 종료가 현재보다 과거일 때 사용합니다. 내정보로 ID를 확인해 관리자가 매칭을 등록하고 서버를 재시작합니다. 공부 명령에는 이름과 구분자를 적지 않습니다. 자정 넘김은 종료 날짜가 필수입니다. 2026년 고정·한국 시간 기준입니다. 공부 내용은 네 자리 숫자로 시작하지 않고 `문제 1234 풀이`처럼 적습니다.

실행은 `/actuator/health`의 UP으로 확인합니다. MM에서는 처리 중 안내 후 최종 결과를 받으며 Notion 링크로 기록과 멤버별 필터 보기를 확인합니다. 시험 기록도 실제 합계에 포함됩니다.

## 오류

| 증상 | 확인 |
|---|---|
| 계정 연결 없음 | 내정보 ID와 MATTERMOST_MEMBER_MAPPINGS 키 일치 |
| 연결 이름 없음/중복 | 매칭 이름과 Members 제목, 중복 멤버 확인 |
| 서버 미설정 | 다섯 설정과 서버 재시작 |
| 인증 실패 | 명령 Token과 MATTERMOST_SLASH_TOKEN |
| 입력 오류 | MMDD·HHmm 네 자리, 필수 내용, 실제 날짜·시각 |
| 자정 기록 거절 | 종료 날짜 명시 |
| 시간 겹침 | 기존 시간대와 링크 확인 |
| 처리 중 이후 결과 없음 | Notion 저장 여부 먼저 확인, callback 접근과 실패 로그 |

누락·형식 오류만 둘째 줄 예시를 표시하며 모든 실패는 경고 아이콘과 필요한 사용자 행동을 안내합니다.
