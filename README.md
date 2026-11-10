# anthill

일반 시민을 위한 모의 투자 서비스. 1단계는 **차트**다: 업비트와 모든 단위(1·3·5·10·15·30·60·240분, 일·주·월)에서 같은 캔들을 그리고, 진행 중인 캔들을 1초마다 움직인다.

- 구조 보고서: [`docs/architecture/2026-11-05-phase1-architecture.html`](docs/architecture/2026-11-05-phase1-architecture.html)

## 저장소 구조

프론트엔드와 백엔드를 한 저장소에서 관리하는 모노레포다. 폴더마다 자기 `Dockerfile`과 `docker-compose.yml`을 가지고, 최상위 `docker-compose.yml`은 둘을 묶어 한 번에 실행한다.

```text
anthill/
├── docker-compose.yml   전체 실행 (backend + frontend)
├── backend/             Gradle 멀티 프로젝트 (services/, libs/), Dockerfile, docker-compose.yml
├── frontend/            React + Vite, Dockerfile, nginx.conf, docker-compose.yml
├── scripts/             업비트 대조·E2E 도구
└── docs/
```

## 구성

| 서비스 | 포트 | 하는 일 |
|---|---|---|
| `edge-gateway` | 8080 | 브라우저의 유일한 입구. `/api/**` → marketdata, WebSocket `/ws` 허브 |
| `marketdata-service` | 8081 | 캔들 저장·정합성 검사·복구·과거 채우기, 진행 중 캔들 1초 발행 |
| `upbit-adapter` | 8082 | 업비트 웹소켓 수신, 업비트 REST(초당 9회 예산) |
| PostgreSQL 18 | 5432 | 마감 캔들, 확인 구간(coverage), 정정 기록, 장애 구간 |
| `frontend` | 5173(Vite) · 3000(컨테이너) | 차트·종목 목록 화면 (`/api`, `/ws`를 게이트웨이로 프록시) |

## 준비물

- Docker (PostgreSQL, 전체 실행)
- JDK 25: 따로 설치하지 않아도 Gradle이 받아 쓴다(foojay 툴체인).
- Node.js 26 LTS: `cd frontend && nvm install && nvm use` (`frontend/.nvmrc`)

## 로컬에서 실행하기

순서대로 각각 다른 터미널에서 실행한다. marketdata가 어댑터보다 먼저 떠도 5초마다 다시 붙는다.

```bash
docker compose up -d db
```

```bash
cd backend && ./gradlew :services:upbit-adapter:bootRun
```

```bash
cd backend && ./gradlew :services:marketdata-service:bootRun
```

```bash
cd backend && ./gradlew :services:edge-gateway:bootRun
```

```bash
cd frontend && npm install && npm run dev
```

브라우저에서 http://localhost:5173 을 연다. 처음 시작하면 marketdata가 종목 목록과 이번 달 일봉을 받고(약 30초, 그동안 종목 목록 시세는 `-`), 남는 요청 예산으로 일·주·월봉 전체와 분봉 최근 7일을 채운다. 다 채우면 marketdata 로그에 `initial backfill complete`가 찍힌다.

> 업비트 REST 한도는 IP당 초당 10회다. 어댑터가 9회를 쓰므로, 같은 IP에서 업비트 API를 따로 부르면 429가 날 수 있다.

## 전체를 컨테이너로 실행하기

저장소 최상위에서 실행한다. 이미지까지 함께 빌드하고, 브라우저에서 http://localhost:3000 을 연다.

```bash
docker compose up -d --build
```

이미지는 `anthill/<서비스>:dev`. 어댑터는 `dev` 프로필로 떠서 끊김 시험용 `POST /admin/stream/pause?seconds=N`을 연다. 멈출 때는 `docker compose down`(데이터는 `pgdata` 볼륨에 남는다).

한쪽만 띄우려면 `backend/`나 `frontend/`에 들어가 같은 명령을 실행한다. 세 파일 모두 프로젝트 이름이 `anthill`이라 같은 DB 볼륨과 네트워크를 쓴다. 그래서 한 폴더에서 `--remove-orphans`를 붙이면 다른 폴더의 컨테이너까지 지워지고, `frontend/`에서 `docker compose down`을 하면 네트워크가 사용 중이라는 경고가 난다. 화면만 멈출 때는 `docker compose stop`을 쓴다.

## 테스트

```bash
cd backend && ./gradlew test
```

```bash
cd frontend && npx vitest run && npx tsc -b
```

백엔드 통합 테스트는 Testcontainers로 PostgreSQL 18을 띄우므로 Docker가 켜져 있어야 한다.

## 업비트와 맞는지 확인하는 도구

**마감 캔들 대조** — DB의 마감 캔들을 업비트 REST와 직접 비교한다. 거래대금 상위 5·하위 5·무작위 5 종목 × 11개 단위, 검사 시한이 지난 캔들만 본다. 불일치가 있으면 종료 코드 1.

```bash
cd backend && ./gradlew :services:marketdata-service:bootRun --args='--spring.profiles.active=verify'
```

**실시간 비교** — 업비트 웹소켓 `candle.1m`과 우리 `/ws`를 같은 종목으로 동시에 기록해 지연(2초 안), 마감 값 일치, 거래량 중복 없음을 판정한다. 종목을 안 주면 거래대금 상위 3개와 하위 2개를 고른다.

```bash
node scripts/compare-live.mjs --minutes 10
```

**운영 지표** (marketdata `/actuator/metrics`): `anthill.reconcile.late`(검사 시한을 넘겨 확인한 구간 수, 장애 구간 제외, 시작 이후 누적), `anthill.reconcile.overdue`(지금 시한을 넘긴 수, 순간값), `anthill.reconcile.lag.seconds`, `anthill.events.dropped`.

대조 도구는 값 불일치(`MISMATCH`)와 함께 한 번도 확인하지 않은 (종목, 단위)(`UNCOVERED`), 확인이 시한을 넘긴 것(`OVERDUE`)도 보고하고, 하나라도 있으면 종료 코드 1이다.

## 작업 방식

- 작업은 최신 main에서 만든 worktree 안에서 하고, main에는 PR로만 합친다(직접 푸시 금지). 규칙은 [`CLAUDE.md`](CLAUDE.md)에 있다.
- 저장소를 받은 뒤 한 번, main 직접 푸시를 막는 git 훅을 켠다.

  ```bash
  git config core.hooksPath .githooks
  ```

- Claude Code에서 `/work <작업 설명>`을 치면 아래 단계를 처음부터 끝까지 순서대로 진행한다. 단계 하나만 하려면 그 단계의 명령을 친다. 절차는 [`.claude/skills/`](.claude/skills/)의 단계별 폴더에, 산출물은 `docs/work/<날짜>-<작업이름>/`에 쌓인다.

  | 명령 | 하는 일 |
  |---|---|
  | `/work-start` | main을 최신으로 받고 worktree를 만든다 |
  | `/work-plan` | 계획서(로직·ER 다이어그램)를 쓰고 확정을 기다린다 |
  | `/work-implement` | 확정된 계획대로 구현한다. 화면을 바꾸는 작업이면 고치기 전 화면을 먼저 캡처한다 |
  | `/work-test` | 테스트할 목록을 확인받고 테스트 코드를 쓴다 |
  | `/work-e2e` | 전체를 띄워 실제 사용자처럼 확인하고 화면을 캡처한다 |
  | `/work-report` | 캡처한 화면으로 보고서를 쓴다(변경은 전·후 비교) |

## 알아 둘 것

- 서비스 사이 `/internal/*`에는 인증이 없다. 공개 운영 전에 `/internal/**`가 인터넷에서 닿지 않게 해야 한다(별도 포트나 네트워크 정책).
- 한국·미국 주식 연동은 다음 단계다(설계 문서 4절).
