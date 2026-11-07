# 1단계: 업비트와 일치하는 차트 (서비스 3개) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 업비트 KRW 전 종목의 1분~월봉 캔들을 수집·저장해서, 업비트와 숫자가 정확히 일치하는 차트를 1초 간격 실시간 갱신으로 보여준다. 시스템은 도메인별 서비스 3개로 나누고, 서비스 간 비동기 통신은 Kafka로 바꿀 수 있는 이벤트 계약 위에서 지금은 REST로 한다.

**Architecture:** `upbit-adapter`가 업비트 웹소켓을 받아 정규화한 이벤트(`market.*`)를 `marketdata-service`로 보내고, 업비트 REST를 우선순위 요청 예산 아래에서 `/internal/candles`로 내놓는다. `marketdata-service`는 진행 중 캔들을 메모리(`LiveCandleBook`)에서 갱신해 1초마다 `chart.*` 이벤트로 `edge-gateway`에 보내고, 마감 캔들을 PostgreSQL에 저장하며, 소스 REST로 확인을 끝낸 구간(coverage)을 기록해 정합성 검사·복구·과거 채우기를 한다. `edge-gateway`는 브라우저의 유일한 입구로 `/api/**`를 라우팅하고 `/ws`로 구독자에게 푸시한다. 서비스는 `libs/market-contract`와 `libs/event-bus`만 공유한다.

**Tech Stack:** JDK 25 (Temurin), Kotlin 2.3.21, Spring Boot 4.1.2, Spring Cloud 2025.1.4 (Gateway Server MVC), Gradle 9.8.2 멀티 프로젝트, Spring MVC + 가상 스레드, Spring WebSocket, JdbcClient, Flyway, PostgreSQL 18, Testcontainers, Micrometer/Actuator, `java.net.http` / Node.js 26 LTS, React 19.3.1, TypeScript 7.0.2, Vite 8.3.x, Vitest 5.1.0, lightweight-charts 5.2.1

**Spec:** `docs/superpowers/specs/2026-11-04-phase1-chart-design.md` (함께 볼 문서: `docs/architecture/2026-11-05-phase1-architecture.html`)

## Global Constraints

- 버전: JDK **25**, Kotlin **2.3.21**, Spring Boot **4.1.2**, Spring Cloud **2025.1.4**, Gradle wrapper **9.8.2**, PostgreSQL **18**(`postgres:18`), Node.js **26 LTS**, React **19.3.1**, TypeScript **7.0.2**(도구가 지원하지 않으면 6.0.3), Vite **8.3.x**, Vitest **5.1.0**, lightweight-charts **5.2.1**.
- 포트: edge-gateway **8080**, marketdata-service **8081**, upbit-adapter **8082**. 브라우저는 8080만 호출한다.
- 서비스끼리는 코드를 직접 참조하지 않는다. 공유는 `libs/market-contract`, `libs/event-bus`뿐이다.
- 동기/비동기 기준: 호출한 쪽이 결과를 받아야 다음 일을 할 수 있으면 동기 REST, 아니면 이벤트. 이벤트는 `EventPublisher`로만 발행한다.
- 이벤트 봉투: `{eventId(UUID), type, version, key, occurredAt, producer, payload}`. type 이름: `market.trade.v1`, `market.candle.v1`, `market.ticker.v1`, `market.stream-status.v1`, `market.instrument.v1`, `chart.candle-update.v1`, `chart.ticker-update.v1`, `realtime.viewing.v1`. key는 종목 ID(상태·종목 이벤트는 소스, viewing은 gateway ID).
- 1단계 전송: 받는 곳마다 단일 스레드가 200ms마다 `POST /internal/events`(JSON 배열)로 보낸다. 최대 3회 재시도 후 버리고 `anthill.events.dropped`를 올린다. 수신 쪽은 최근 eventId로 중복을 버린다.
- 종목 ID: `소스:심볼` 문자열(`UPBIT:KRW-BTC`).
- 마감 캔들은 업비트 REST 응답과 **모든 필드(시가·고가·저가·종가·거래량·거래대금)가 정확히 일치**하고 **개수도 같아야** 한다.
- 가격·거래량·거래대금은 `NUMERIC`으로 저장하고, 서비스 사이·브라우저로는 **문자열**로 보낸다. float/double 변환 금지. 숫자 비교는 **값 기준**(`compareTo == 0`).
- 시각은 UTC 저장, 화면은 KST. 캔들 식별자 `(종목 ID, 단위, 캔들 시작 UTC)`.
- 단위: **1, 3, 5, 10, 15, 30, 60, 240분 / 일 / 주 / 월**. 초봉·연봉 없음. 경계(UTC): 분봉 UTC 정렬(240분봉 00·04·08…시), 일봉 00:00, 주봉 월요일 00:00, 월봉 1일 00:00.
- 진행 중 캔들은 **1초마다** 갱신(그 1초에 체결이 있을 때), 업비트 웹소켓 수신 기준 **2초 안에** 화면 반영. **업비트가 이긴다**: 분봉 이벤트의 마지막 체결 시각이 우리가 반영한 마지막 체결 시각 이상이면 업비트 값을 그대로 쓰고, 더 오래된 이벤트일 때만 큰 쪽을 유지한다. 체결은 `sequential_id`로 중복 제거. 주·월봉은 마감된 일봉 + 티커 당일 일봉의 합으로 만든다. 거래 없는 시간을 임의 값으로 채우지 않는다.
- 장애 구간(업비트 끊김 시점 ~ 복구 완료)은 `outage` 테이블에 기록하고, 검사 시한 판정에서 뺀다(불일치 판정에는 포함).
- 업비트 REST: 캔들 그룹 IP당 초당 10회, 1회 최대 200개, `to` 배타적. 어댑터 상한 **초당 9회**, 우선순위 `USER > RECOVERY > RECONCILE > BACKFILL`.
- 정합성 검사 시한: 1·3·5분봉은 마감 후 **5분 이내**, 10분 이상 단위는 마감 후 **다음 한 주기 이내**.
- 미리 채우기: 일·주·월봉 전체 기간, 분 단위 **최근 7일**. 복구 중에는 정기 검사를 멈춘다. 5분 끊김 복구는 **10분 이내**.
- 1단계는 외부 비공개: 인증·클라이언트별 요청 제한·HTTPS 없음. Kafka 도입 없음.

## Review Focus

1. **같은 값, 다른 scale**: `108328000`과 `108328000.00000000`은 같은 값이다. 다르다고 보면 정합성 검사가 영원히 "정정"을 반복한다. 서비스 사이 JSON에서도 숫자가 문자열로 정확히 오가야 한다. → Task 2, Task 6 테스트.
2. **거래가 드문 종목**: 1분봉 200개가 며칠(KRW-OPG는 3,494분)에 걸친다. 과거 조회가 같은 구간을 다시 받거나 무한 반복하면 안 되고, 캔들이 하나도 없는 검사 구간도 coverage가 늘어나야 한다. → Task 6, Task 8 테스트.
3. **늦게 온 분봉 이벤트**: 이미 마감되고 REST로 검증된 캔들에 대한 이벤트가 늦게 오면, 검증된 값을 덮어쓰거나 그 캔들을 다시 "진행 중"으로 만들면 안 된다. 반대로 우리가 반영한 체결보다 새로운 분봉 이벤트는 우리 값이 더 커도 업비트 값으로 맞춰야 한다. → Task 7 테스트.
4. **같은 체결이 두 번 옴**: 서비스 간 재전송(같은 eventId)이나 업비트 재연결·재구독(같은 `sequential_id`)으로 같은 체결이 두 번 와도 거래량이 두 배가 되면 안 된다. → Task 3, Task 7 테스트.
5. **시작 순서와 재시작**: marketdata가 어댑터보다 먼저 떠도 죽지 않고 기다렸다 붙어야 하고, 어댑터가 재시작하면(새 `stream-status`) 그 사이 빈 구간을 복구해야 한다. → Task 7, Task 8 테스트.

---

## File Structure

```
settings.gradle.kts                    멀티 프로젝트 목록
build.gradle.kts                       공통 설정 (group, toolchain 25, Kotlin 옵션)
gradle/libs.versions.toml              버전 카탈로그
gradle/wrapper/                        Gradle 9.8.2 wrapper
docker-compose.yml                     PostgreSQL 18
docker-compose.full.yml                24시간 검증용: DB + 서비스 3개 (Task 12)
libs/market-contract/                  com.anthill.contract
  InstrumentId.kt  CandleUnit.kt  Candle.kt  Decimals.kt  Priority.kt
  SourceTypes.kt                       InstrumentInfo, TickerSnapshot, StreamStatus
  Events.kt                            이벤트 payload 타입 + EventTypes 상수
  ContractJson.kt                      공통 JsonMapper (숫자↔문자열, Instant ISO, InstrumentId·CandleUnit 문자열)
libs/event-bus/                        com.anthill.eventbus
  EventEnvelope.kt  EventPublisher.kt  HttpEventTransport.kt  EventHandler.kt  EventInbox.kt
services/upbit-adapter/                com.anthill.upbit
  UpbitAdapterApplication.kt
  rest/RequestBudget.kt  rest/UpbitRestClient.kt
  stream/UpbitStreamParser.kt  stream/UpbitStreamClient.kt  stream/StreamEventBridge.kt
  instruments/InstrumentRefresher.kt
  api/SourceController.kt  api/AdminController.kt  config/AdapterConfig.kt
services/marketdata-service/           com.anthill.marketdata
  MarketdataApplication.kt  config/MarketdataConfig.kt
  source/SourceClient.kt               SourceClient 인터페이스 + HttpSourceClient
  candle/InstrumentRepository.kt  CandleRepository.kt  CoverageRepository.kt  CorrectionLog.kt
  candle/CandleSync.kt  candle/CandleHistoryService.kt
  live/LiveCandleBook.kt  TickerCache.kt  StreamStatusTracker.kt  LiveIngest.kt  LivePublisher.kt  ViewingTracker.kt
  ingest/EventsController.kt  ingest/Handlers.kt
  sync/ReconcilePlanner.kt  Reconciler.kt  Recovery.kt  InitialBackfill.kt  Startup.kt
  api/CandleController.kt  api/MarketController.kt  api/ApiDtos.kt
  tools/VerifyCandlesRunner.kt
  resources/db/migration/V1__init.sql
services/edge-gateway/                 com.anthill.gateway
  EdgeGatewayApplication.kt
  realtime/SubscriptionRegistry.kt  StreamWebSocketHandler.kt  GatewayFanout.kt  ViewingPublisher.kt
  realtime/EventsController.kt  config/WebSocketConfig.kt  config/GatewayConfig.kt
frontend/                              Vite react-ts
  src/api/types.ts  client.ts  stream.ts
  src/chart/time.ts  merge.ts  CandleChart.tsx  Legend.tsx
  src/markets/MarketList.tsx  src/App.tsx
```
테스트는 각 모듈의 `src/test/kotlin`에 같은 패키지로 둔다. `FakeSource`(marketdata 테스트용)는 업비트 REST 의미(`to` 배타적, 최신→과거, 최대 200개, 상장 이전 빈 배열)를 흉내 내고 호출 수를 센다.

---

### Task 1: 개발 환경과 멀티 프로젝트 골격

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `.gitignore`, `docker-compose.yml`
- Create: `libs/market-contract/build.gradle.kts`, `libs/event-bus/build.gradle.kts`
- Create: `services/{upbit-adapter,marketdata-service,edge-gateway}/build.gradle.kts`, 각 `*Application.kt`, `src/main/resources/application.yml`, `src/test/resources/application.yml`
- Create: `services/marketdata-service/src/main/resources/db/migration/V1__init.sql`
- Test: 각 서비스 `*ApplicationTests.kt`

**Interfaces:**
- Produces: Gradle 경로 `:libs:market-contract`, `:libs:event-bus`, `:services:upbit-adapter`, `:services:marketdata-service`, `:services:edge-gateway`. 설정 키(아래 Step 6). 테이블 `instrument`, `candle`, `coverage`, `sync_correction`, `outage`.

- [ ] **Step 1: 개발 환경 확인** — `java -version`이 25, `node -v`가 v26.x인지 확인한다. 아니면 사용자에게 알리고 허락을 받아 Temurin 25와 Node.js 26 LTS를 설치한다. 셸에서 `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`.
- [ ] **Step 2: 참고용 프로젝트 생성** — wrapper와 의존성 좌표를 그대로 가져오기 위해 쓴다.

```bash
curl -s https://start.spring.io/starter.zip -d type=gradle-project-kotlin -d language=kotlin \
  -d bootVersion=4.1.2 -d javaVersion=25 -d groupId=com.anthill -d artifactId=ref -d name=ref \
  -d dependencies=web,websocket,jdbc,flyway,postgresql,actuator,testcontainers,cloud-gateway -o /tmp/ref.zip
unzip -q /tmp/ref.zip -d /tmp/ref
```
`/tmp/ref/gradle/wrapper`, `gradlew`, `gradlew.bat`을 루트로 복사한다(9.8.2). Testcontainers·Flyway·Gateway 의존성 좌표는 `/tmp/ref/build.gradle.kts`의 것을 쓴다.
- [ ] **Step 3: `gradle/libs.versions.toml`** — `spring-boot = "4.1.2"`, `kotlin = "2.3.21"`, `spring-dependency-management = "1.1.7"`, `spring-cloud = "2025.1.4"`, plugins `kotlin-jvm`, `kotlin-spring`, `spring-boot`, `spring-dependency-management`.
- [ ] **Step 4: 루트 빌드** — 모든 하위 프로젝트: group `com.anthill`, `kotlin { jvmToolchain(25); compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }`, JUnit Platform. `libs/*`는 `kotlin-jvm` + `java-library` + dependency-management로 Spring Boot BOM을 import(Jackson 3 `tools.jackson.*` 버전 정렬). `libs/market-contract` 의존성: `tools.jackson.core:jackson-databind`, `tools.jackson.module:jackson-module-kotlin`. `libs/event-bus`: `api(project(":libs:market-contract"))`.
- [ ] **Step 5: 서비스 빌드 파일** — 공통: `spring-boot`, `spring-dependency-management`, `kotlin-spring` 플러그인, `implementation(project(":libs:market-contract"))`, `implementation(project(":libs:event-bus"))`, `spring-boot-starter-actuator`, `kotlin-reflect`, test `spring-boot-starter-test`, `kotlin-test-junit5`.
  - upbit-adapter: `spring-boot-starter-web`
  - marketdata-service: `spring-boot-starter-web`, `spring-boot-starter-jdbc`, Flyway(core + postgresql), `postgresql`, Testcontainers(참고 프로젝트 좌표)
  - edge-gateway: `spring-cloud-starter-gateway-server-webmvc`, `spring-boot-starter-websocket`, Spring Cloud BOM `2025.1.4`
- [ ] **Step 6: 설정 파일**

```yaml
# upbit-adapter
server.port: 8082
spring.threads.virtual.enabled: true
anthill:
  upbit: { rest-base-url: https://api.upbit.com/v1, ws-url: wss://api.upbit.com/websocket/v1, rest-rate-per-second: 9 }
  stream.enabled: true
  events.subscribers: [ { types: [ "market." ], url: http://localhost:8081/internal/events } ]
# marketdata-service
server.port: 8081
spring.threads.virtual.enabled: true
spring.datasource: { url: jdbc:postgresql://localhost:5432/anthill, username: anthill, password: anthill }
anthill:
  sources: { UPBIT: http://localhost:8082 }
  jobs.enabled: true
  backfill.minute-days: 7
  live.close-grace: 10s
  events.subscribers: [ { types: [ "chart." ], url: http://localhost:8080/internal/events } ]
management.endpoints.web.exposure.include: health,metrics
# edge-gateway
server.port: 8080
spring.threads.virtual.enabled: true
spring.cloud.gateway.server.webmvc.routes:
  - { id: marketdata-api, uri: http://localhost:8081, predicates: [ "Path=/api/**" ] }
anthill.events.subscribers: [ { types: [ "realtime." ], url: http://localhost:8081/internal/events } ]
```
라우트 속성 이름은 Spring Cloud 2025.1 문서와 다르면 문서를 따른다. 테스트용 `application.yml`: `anthill.stream.enabled: false`, `anthill.jobs.enabled: false`, `anthill.events.subscribers: []`.
- [ ] **Step 7: `V1__init.sql`**

```sql
create table instrument (id text primary key, source text not null, symbol text not null,
  korean_name text not null, english_name text not null, active boolean not null, updated_at timestamptz not null);
create table candle (instrument_id text not null, unit text not null, start_at timestamptz not null,
  open numeric not null, high numeric not null, low numeric not null, close numeric not null,
  volume numeric not null, amount numeric not null, primary key (instrument_id, unit, start_at));
create table coverage (instrument_id text not null, unit text not null, from_at timestamptz not null,
  to_at timestamptz not null, primary key (instrument_id, unit, from_at), check (from_at < to_at));
create table sync_correction (id bigserial primary key, instrument_id text not null, unit text not null,
  start_at timestamptz not null, kind text not null, before jsonb, after jsonb, detected_at timestamptz not null);
create table outage (id bigserial primary key, source text not null, from_at timestamptz not null, to_at timestamptz);
```
- [ ] **Step 8: `docker-compose.yml`** — 서비스 `db`: `postgres:18`, DB·사용자·비밀번호 `anthill`, `5432:5432`, 볼륨 `pgdata`.
- [ ] **Step 9: 컨텍스트 테스트** — 세 서비스 각각 `contextLoads()`. marketdata는 Testcontainers(`@ServiceConnection` PostgreSQL 18)로 띄우고 다섯 테이블이 있는지 `information_schema`로 확인.
- [ ] **Step 10: 실행** — `docker compose up -d && ./gradlew build` → BUILD SUCCESSFUL.
- [ ] **Step 11: Commit** — `git commit -m "chore: multi-project skeleton on JDK 25 and Spring Boot 4.1"`

---

### Task 2: 공통 계약 `libs/market-contract`

**Files:**
- Create: `libs/market-contract/src/main/kotlin/com/anthill/contract/{InstrumentId,CandleUnit,Candle,Decimals,Priority,SourceTypes,Events,ContractJson}.kt`
- Test: `libs/market-contract/src/test/kotlin/com/anthill/contract/{CandleUnitTest,DecimalsTest,InstrumentIdTest,ContractJsonTest}.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @JvmInline value class InstrumentId(val value: String) {      // "UPBIT:KRW-BTC"
    val source: String; val symbol: String
    companion object { fun of(source: String, symbol: String): InstrumentId; fun parse(v: String): InstrumentId } // 접두어 없으면 IllegalArgumentException
  }
  enum class CandleUnit(val code: String, val minutes: Int?) {
    M1("1m",1), M3("3m",3), M5("5m",5), M10("10m",10), M15("15m",15), M30("30m",30), M60("60m",60), M240("240m",240),
    DAY("day",null), WEEK("week",null), MONTH("month",null);
    fun floor(t: Instant): Instant; fun next(start: Instant): Instant
    val reconcileSlack: Duration      // M1·M3·M5 = 3분, 그 외 = 한 주기의 절반 (MONTH = 15일)
    val reconcileDeadline: Duration   // M1·M3·M5 = 5분, 그 외 = 한 주기 (MONTH = 31일)
    companion object { fun fromCode(code: String): CandleUnit? }
  }
  data class Candle(val instrument: InstrumentId, val unit: CandleUnit, val start: Instant,
    val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val close: BigDecimal,
    val volume: BigDecimal, val amount: BigDecimal) { fun sameValuesAs(o: Candle): Boolean }
  object Decimals { fun same(a: BigDecimal, b: BigDecimal): Boolean; fun format(x: BigDecimal): String } // stripTrailingZeros().toPlainString()
  enum class Priority { USER, RECOVERY, RECONCILE, BACKFILL }
  data class InstrumentInfo(val id: InstrumentId, val koreanName: String, val englishName: String)
  data class TickerSnapshot(val instrument: InstrumentId, val tradePrice: BigDecimal, val signedChangeRate: BigDecimal,
    val accTradePrice24h: BigDecimal, val day: Candle)
  data class StreamStatus(val connected: Boolean, val since: Instant?)
  // 이벤트 payload
  data class TradeEvent(val instrument: InstrumentId, val price: BigDecimal, val volume: BigDecimal, val tradedAt: Instant, val sequentialId: Long)
  data class SourceCandleEvent(val candle: Candle, val lastTradeAt: Instant, val snapshot: Boolean)
  data class TickerEvent(val ticker: TickerSnapshot)
  data class StreamStatusEvent(val source: String, val connected: Boolean, val at: Instant)
  data class InstrumentListEvent(val source: String, val instruments: List<InstrumentInfo>)
  enum class CandleState { LIVE, CLOSED, CORRECTED, REMOVED }
  data class CandleUpdateEvent(val instrument: InstrumentId, val unit: CandleUnit, val start: Instant, val candle: Candle?, val state: CandleState)
  data class ViewingEvent(val gatewayId: String, val instruments: Set<InstrumentId>, val at: Instant)
  object EventTypes { const val MARKET_TRADE = "market.trade.v1"; MARKET_CANDLE = "market.candle.v1"; MARKET_TICKER = "market.ticker.v1"
    MARKET_STREAM_STATUS = "market.stream-status.v1"; MARKET_INSTRUMENT = "market.instrument.v1"
    CHART_CANDLE_UPDATE = "chart.candle-update.v1"; CHART_TICKER_UPDATE = "chart.ticker-update.v1"; REALTIME_VIEWING = "realtime.viewing.v1" }
  object ContractJson { fun mapper(): JsonMapper }  // BigDecimal ↔ JSON 문자열(Decimals.format), Instant ISO-8601 Z, InstrumentId·CandleUnit(code)·enum 문자열
  ```
  `Candle`의 JSON 형태: `{"instrument","unit","time","open","high","low","close","volume","amount"}` (`time` = start). 브라우저 API도 이 형태를 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun `240m aligns to UTC 0-4-8`() =
  assertEquals(Instant.parse("2026-11-04T04:00:00Z"), CandleUnit.M240.floor(Instant.parse("2026-11-04T07:59:59Z")))
@Test fun `week starts Monday 00 UTC`() {
  assertEquals(Instant.parse("2026-11-02T00:00:00Z"), CandleUnit.WEEK.floor(Instant.parse("2026-11-08T23:59:59.999Z")))
  assertEquals(Instant.parse("2026-11-09T00:00:00Z"), CandleUnit.WEEK.floor(Instant.parse("2026-11-09T00:00:00Z")))
}
@Test fun `month floor and next`() {
  assertEquals(Instant.parse("2026-02-01T00:00:00Z"), CandleUnit.MONTH.floor(Instant.parse("2026-02-28T23:59:59Z")))
  assertEquals(Instant.parse("2026-03-01T00:00:00Z"), CandleUnit.MONTH.next(Instant.parse("2026-02-01T00:00:00Z")))
}
@Test fun `same value different scale`() = assertTrue(Decimals.same(BigDecimal("41983565546.39229000"), BigDecimal("41983565546.3922900000000000")))
@Test fun `format strips zeros without exponent`() { assertEquals("108328000", Decimals.format(BigDecimal("108328000.00000000"))); assertEquals("0.00012", Decimals.format(BigDecimal("0.000120"))) }
@Test fun `instrument id parses source and symbol`() { val id = InstrumentId.parse("UPBIT:KRW-BTC"); assertEquals("UPBIT", id.source); assertEquals("KRW-BTC", id.symbol) }
@Test fun `instrument id without prefix is rejected`() { assertThrows<IllegalArgumentException> { InstrumentId.parse("KRW-BTC") } }
@Test fun `candle json keeps decimals as exact strings`() // Candle → JSON에 "amount":"41983565546.39229" 문자열, 다시 읽은 값 Decimals.same
@Test fun `json never emits numbers for decimals`()       // TradeEvent 직렬화 결과에 숫자 리터럴 price/volume 없음
```

- [ ] **Step 2: 실패 확인** — `./gradlew :libs:market-contract:test` → FAIL.
- [ ] **Step 3: 구현** — 분 단위 `floor`는 epoch 초를 `minutes×60`으로 내림. JSON은 Jackson 3 `JsonMapper.builder()` + Kotlin 모듈 + BigDecimal·InstrumentId·CandleUnit용 serializer/deserializer.
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(contract): instrument ids, candle units, decimal rules and event payloads"`

---

### Task 3: 이벤트 전송 `libs/event-bus`

**Files:**
- Create: `libs/event-bus/src/main/kotlin/com/anthill/eventbus/{EventEnvelope,EventPublisher,HttpEventTransport,EventHandler,EventInbox}.kt`
- Test: `libs/event-bus/src/test/kotlin/com/anthill/eventbus/{HttpEventTransportTest,EventInboxTest}.kt`

**Interfaces:**
- Consumes: `ContractJson.mapper()`
- Produces:
  ```kotlin
  data class EventEnvelope(val eventId: UUID, val type: String, val version: Int, val key: String,
    val occurredAt: Instant, val producer: String, val payload: JsonNode)
  fun interface EventPublisher { fun publish(type: String, key: String, payload: Any) }
  data class Subscriber(val types: List<String>, val url: URI)          // types는 접두어 ("market.")
  class HttpEventTransport(producer: String, subscribers: List<Subscriber>, mapper: JsonMapper,
      clock: Clock, flushInterval: Duration = Duration.ofMillis(200), maxRetries: Int = 3,
      http: HttpClient = HttpClient.newHttpClient(), onDropped: (Int) -> Unit = {}) : EventPublisher, AutoCloseable
  interface EventHandler<T : Any> { val type: String; val payloadType: Class<T>; fun handle(key: String, payload: T, envelope: EventEnvelope) }
  data class InboxResult(val handled: Int, val duplicates: Int, val unknown: Int, val failed: Int)
  class EventInbox(handlers: List<EventHandler<*>>, mapper: JsonMapper, remember: Int = 200_000) {
    fun accept(batch: List<EventEnvelope>): InboxResult      // 배열 순서대로 처리, 핸들러 예외는 failed로 세고 다음 이벤트 계속
  }
  ```
  `version`은 type 끝의 `vN`과 같다. 각 서비스는 `POST /internal/events` 컨트롤러에서 `EventInbox.accept`를 부르고 202를 돌려준다(컨트롤러는 서비스에 둔다).

- [ ] **Step 1: 실패하는 테스트 작성** (`com.sun.net.httpserver.HttpServer`로 받는 쪽 흉내)

```kotlin
@Test fun `publishes within flush interval as one batch`()     // 5건 발행 → 300ms 안에 POST 1건, 배열 길이 5, 발행 순서 유지
@Test fun `routes by type prefix`()                            // market.* 는 A로, chart.* 는 B로만
@Test fun `retries then drops and reports`()                   // 서버가 계속 500 → 4번 시도(최초+3) 뒤 onDropped(5)
@Test fun `keeps order across batches for one subscriber`()    // 첫 묶음 재시도 중 발행된 이벤트는 첫 묶음 뒤에 감
@Test fun `inbox skips duplicate eventId`()                    // 같은 envelope 두 번 → handled 1, duplicates 1
@Test fun `inbox counts unknown type and continues`()
@Test fun `handler failure does not stop batch`()
```

- [ ] **Step 2: 실패 확인** — `./gradlew :libs:event-bus:test` → FAIL.
- [ ] **Step 3: 구현** — 받는 곳마다 큐 하나와 가상 스레드 하나. 재시도 백오프 200ms·400ms·800ms. 중복 기억은 크기 제한 LRU(`LinkedHashMap` access-order).
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(event-bus): envelope, batched HTTP transport and dedup inbox"`

---

### Task 4: upbit-adapter — REST 클라이언트, 요청 예산, 소스 조회 API

**Files:**
- Create: `services/upbit-adapter/src/main/kotlin/com/anthill/upbit/rest/{RequestBudget,UpbitRestClient}.kt`, `api/SourceController.kt`, `config/AdapterConfig.kt`
- Test: `rest/RequestBudgetTest.kt`, `rest/UpbitRestClientTest.kt`, `api/SourceControllerTest.kt`
- Test resources: `upbit/candles-minutes-1.json`, `upbit/candles-days.json`, `upbit/ticker-all.json`, `upbit/market-all.json` (Step 1에서 실제 응답 저장)

**Interfaces:**
- Consumes: `InstrumentId`, `CandleUnit`, `Candle`, `Priority`, `InstrumentInfo`, `TickerSnapshot`, `ContractJson`
- Produces:
  ```kotlin
  class RequestBudget(ratePerSecond: Int, timeSource: TimeSource = TimeSource.Monotonic) { fun <T> run(priority: Priority, block: () -> T): T }
  interface UpbitRestClient {
    fun markets(): List<InstrumentInfo>                                     // KRW- 만, id = UPBIT:<market>
    fun candles(market: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> // 최신→과거, to 배타적, count ≤ 200
    fun tickers(): List<TickerSnapshot>                                      // /ticker/all?quote_currencies=KRW, day = 티커 값으로 만든 DAY 캔들(start = trade_date 00:00Z)
  }
  class HttpUpbitRestClient(baseUrl: String, budget: RequestBudget, http: HttpClient) : UpbitRestClient
  // GET /internal/candles?instrument=UPBIT:KRW-BTC&unit=1m&to=2026-11-05T11:23:00Z&count=200&priority=USER → List<Candle> JSON(최신→과거)
  // GET /internal/instruments → List<InstrumentInfo>,  GET /internal/tickers → List<TickerSnapshot>
  // 400: 접두어가 UPBIT가 아님, unit 오류, count ∉ 1..200
  ```

- [ ] **Step 1: 실제 응답 저장** — `curl -s 'https://api.upbit.com/v1/candles/minutes/1?market=KRW-BTC&count=3' > .../upbit/candles-minutes-1.json` (나머지 셋도 같은 방식).
- [ ] **Step 2: 실패하는 테스트 작성**

```kotlin
@Test fun `parses decimals exactly`()            // 저장한 JSON의 숫자 문자열과 BigDecimal 값이 Decimals.same (Upbit 응답은 JSON 숫자이므로 BigDecimal로 직접 읽어야 함)
@Test fun `candle start is candle_date_time_utc as UTC`()
@Test fun `ticker day candle starts at trade_date 00Z`()
@Test fun `to is sent as ISO UTC`()              // 로컬 HttpServer로 요청 캡처: to=2026-11-05T11:23:00
@Test fun `retries after 429 with backoff`()     // 첫 응답 429, 두 번째 200
@Test fun `budget never exceeds rate`()          // 가짜 TimeSource, 20회 → 어떤 1초 창에도 9회 이하
@Test fun `higher priority goes first`()         // BACKFILL 3개 대기 중 USER 1개 → USER가 다음 슬롯
@Test fun `internal candles returns strings newest first`()   // MockMvc, 숫자 필드가 JSON 문자열
@Test fun `rejects non upbit instrument`()       // KRX:005930 → 400
```

- [ ] **Step 3: 실패 확인** — `./gradlew :services:upbit-adapter:test` → FAIL.
- [ ] **Step 4: 구현** — `java.net.http.HttpClient`. 업비트 JSON은 BigDecimal 필드로 직접 매핑(float 경유 금지). 429면 지수 백오프 최대 3회.
- [ ] **Step 5: 통과 확인** → PASS.
- [ ] **Step 6: Commit** — `git commit -m "feat(upbit-adapter): REST client, prioritized budget and source API"`

---

### Task 5: upbit-adapter — 웹소켓 수집, 이벤트 발행, 종목 갱신

**Files:**
- Create: `stream/{UpbitStreamParser,UpbitStreamClient,StreamEventBridge}.kt`, `instruments/InstrumentRefresher.kt`, `api/AdminController.kt`; Modify: `api/SourceController.kt`, `config/AdapterConfig.kt`
- Test: `stream/UpbitStreamParserTest.kt`, `stream/UpbitStreamClientTest.kt`, `stream/StreamEventBridgeTest.kt`, `instruments/InstrumentRefresherTest.kt`, `api/AdminControllerTest.kt`
- Test resources: `upbit/stream-candle-1m.json`, `stream-trade.json`, `stream-ticker.json` (Step 1에서 실제 메시지 저장)

**Interfaces:**
- Consumes: Task 4 전부, `EventPublisher`, `HttpEventTransport`, `EventTypes`
- Produces:
  ```kotlin
  sealed interface UpbitMessage
  data class UpbitCandleMsg(val candle: Candle, val lastTradeAt: Instant, val snapshot: Boolean) : UpbitMessage
  data class UpbitTradeMsg(val trade: TradeEvent) : UpbitMessage
  data class UpbitTickerMsg(val ticker: TickerSnapshot) : UpbitMessage
  interface StreamListener { fun onMessage(m: UpbitMessage); fun onConnected(at: Instant); fun onDisconnected(at: Instant) }
  object UpbitStreamParser { fun subscribeMessage(markets: List<String>, ticket: String): String; fun parse(text: String): UpbitMessage? }
  class UpbitStreamClient(url: String, listener: StreamListener, clock: Clock,
      connect: (WebSocket.Listener) -> CompletableFuture<WebSocket>) {
    fun start(markets: List<String>); fun resubscribe(markets: List<String>); fun pause(d: Duration); val status: StreamStatus
  }
  class StreamEventBridge(publisher: EventPublisher) : StreamListener
    // candle → market.candle.v1(key 종목 ID), trade → market.trade.v1, ticker → market.ticker.v1, 연결/끊김 → market.stream-status.v1(key "UPBIT")
  class InstrumentRefresher(rest: UpbitRestClient, stream: UpbitStreamClient, publisher: EventPublisher) {
    @Scheduled(fixedDelay = 600_000) fun refresh()   // 목록이 바뀌면 stream.resubscribe + market.instrument.v1 발행
  }
  // GET /internal/stream-status → StreamStatus,  @Profile("dev") POST /admin/stream/pause?seconds=300
  ```
  시작 순서(`anthill.stream.enabled=true`): `InstrumentRefresher.refresh()` → `UpbitStreamClient.start(목록)`.

- [ ] **Step 1: 실제 메시지 저장과 타입 확인** — node 26 내장 `WebSocket`으로 `candle.1m`·`candle.3m`·…·`candle.240m`·`trade`·`ticker`를 구독해 각 1건씩 저장한다(바이너리 프레임은 UTF-8 디코드). 8개 캔들 타입이 모두 오는지 확인하고, 안 오는 타입이 있으면 계획 작성자에게 보고한다.
- [ ] **Step 2: 실패하는 테스트 작성**

```kotlin
@Test fun `subscribe message has eight candle types plus trade and ticker`()   // type 집합 10개, codes == 입력
@Test fun `parses candle message exactly`()        // unit M1, 값 Decimals.same, snapshot 플래그
@Test fun `parses trade timestamp in millis`()
@Test fun `unknown type returns null`()
@Test fun `reconnects with backoff and reports status`()  // 가짜 connect: 첫 연결 후 onClose → onDisconnected → 1초 뒤 재연결 → onConnected, status.since 갱신
@Test fun `pause suppresses reconnect for duration`()
@Test fun `bridge publishes typed events with keys`()     // 가짜 EventPublisher: trade → (market.trade.v1, "UPBIT:KRW-BTC"), 연결 → (market.stream-status.v1, "UPBIT")
@Test fun `refresher resubscribes and publishes only on change`()
@Test fun `stream status endpoint reflects client`()
```

- [ ] **Step 3: 실패 확인** — `./gradlew :services:upbit-adapter:test` → FAIL.
- [ ] **Step 4: 구현** — `java.net.http.WebSocket`, `onBinary` 조각을 모아 UTF-8 디코드. 재연결 1s→2s→…→30s. 구독 메시지는 연결당 1건. `HttpEventTransport` 빈은 `anthill.events.subscribers`로 만들고, `onDropped`는 Micrometer 카운터 `anthill.events.dropped`를 올린다(marketdata·gateway도 같은 방식).
- [ ] **Step 5: 통과 확인** → PASS.
- [ ] **Step 6: Commit** — `git commit -m "feat(upbit-adapter): websocket ingest, event bridge and instrument refresh"`

---

### Task 6: marketdata — 저장소, 소스 조회, 구간 동기화, 과거 조회 API

**Files:**
- Create: `source/SourceClient.kt`, `candle/{InstrumentRepository,CandleRepository,CoverageRepository,CorrectionLog,CandleSync,CandleHistoryService}.kt`, `api/{CandleController,ApiDtos}.kt`, `config/MarketdataConfig.kt`, 임시 `candle/{NoLiveView,NoopChangeSink}.kt`
- Test: `candle/*RepositoryTest.kt`, `candle/CandleSyncTest.kt`, `candle/CandleHistoryServiceTest.kt`, `api/CandleControllerTest.kt`, `src/test/kotlin/com/anthill/marketdata/FakeSource.kt`

**Interfaces:**
- Consumes: `market-contract` 전부
- Produces:
  ```kotlin
  interface SourceClient {
    fun candles(instrument: InstrumentId, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> // 최신→과거
    fun instruments(source: String): List<InstrumentInfo>
    fun tickers(source: String): List<TickerSnapshot>
    fun streamStatus(source: String): StreamStatus
  }
  class HttpSourceClient(baseUrls: Map<String, URI>, mapper: JsonMapper, http: HttpClient) : SourceClient // 소스 = instrument.source
  class InstrumentRepository(jdbc: JdbcClient) { fun sync(source: String, list: List<InstrumentInfo>, at: Instant): Boolean; fun active(): List<InstrumentInfo>; fun exists(id: InstrumentId): Boolean }
  class CandleRepository(jdbc: JdbcClient) {
    fun upsert(candles: Collection<Candle>); fun findRange(i: InstrumentId, u: CandleUnit, from: Instant, to: Instant): List<Candle> // [from,to) 오름차순
    fun findBefore(i: InstrumentId, u: CandleUnit, to: Instant, limit: Int): List<Candle>  // start < to 중 최신 limit개, 오름차순
    fun delete(i: InstrumentId, u: CandleUnit, starts: Collection<Instant>)
  }
  class CoverageRepository(jdbc: JdbcClient) {
    fun add(i: InstrumentId, u: CandleUnit, from: Instant, to: Instant)   // 겹치거나 맞닿으면 병합
    fun coveredStartFor(i: InstrumentId, u: CandleUnit, end: Instant): Instant?  // from < end ≤ to 인 구간의 from
    fun end(i: InstrumentId, u: CandleUnit): Instant?; fun start(i: InstrumentId, u: CandleUnit): Instant?
  }
  class CorrectionLog(jdbc: JdbcClient, meter: MeterRegistry) { fun record(kind: String, before: Candle?, after: Candle?) } // anthill.sync.corrections{unit,kind}
  fun interface CandleChangeSink { fun onCorrected(i: InstrumentId, u: CandleUnit, upserted: List<Candle>, deleted: List<Instant>) }
  data class SyncResult(val upserted: List<Candle>, val deleted: List<Instant>, val reachedHistoryStart: Boolean)
  class CandleSync(source: SourceClient, candles: CandleRepository, coverage: CoverageRepository, sink: CandleChangeSink, corrections: CorrectionLog) {
    fun syncWindow(i: InstrumentId, u: CandleUnit, from: Instant, to: Instant, priority: Priority, audit: Boolean): SyncResult
    fun syncBefore(i: InstrumentId, u: CandleUnit, to: Instant, priority: Priority): SyncResult
  }
  interface LiveView { fun current(i: InstrumentId, u: CandleUnit): Candle?; fun streamConnectedSince(source: String): Instant? }
  data class CandlePage(val candles: List<Candle>, val live: Candle?, val hasMore: Boolean)
  class CandleHistoryService(candles, coverage, sync, live: LiveView, clock: Clock) { fun page(i: InstrumentId, u: CandleUnit, to: Instant?, count: Int): CandlePage }
  // GET /api/candles?market=UPBIT:KRW-BTC&unit=1m&to=…Z&count=200 → {"market","unit","candles":[Candle…오름차순],"live":Candle|null,"hasMore"}
  // 400: unit·count(1..200)·to 형식 오류 / 404: 모르는 종목
  ```
  임시 빈 `NoLiveView`(current=null, streamConnectedSince=null)와 `NoopChangeSink`는 Task 7에서 삭제한다.

- [ ] **Step 1: 실패하는 테스트 작성** (FakeSource + Testcontainers)

```kotlin
@Test fun `numeric round trip keeps value`()
@Test fun `coverage merges adjacent and overlapping`()         // [0,10)+[10,20)+[15,30) → [0,30)
@Test fun `coveredStartFor uses exclusive start inclusive end`() // [10,20): end=20 → 10, end=10 → null, end=21 → null
@Test fun `syncWindow makes db equal to source`()              // 틀린 값 1·없는 캔들 1·소스에 없는 캔들 1 → 갱신·추가·삭제, coverage [from,to)
@Test fun `syncWindow pages when window holds over 200`()      // 500분 연속 → callCount 3
@Test fun `equal values with different scale are not corrections`() // DB 108328000, 소스 108328000.00000000 → upserted 비어 있음
@Test fun `empty window still extends coverage`()
@Test fun `syncBefore at history start covers from EPOCH`()    // 소스에 150개뿐 → reachedHistoryStart, coverage [EPOCH, to)
@Test fun `sparse market pages back without refetching`()     // 200개가 3,494분에 흩어짐: page 두 번 → 두 번째는 호출 증가 없음
@Test fun `gaps are not filled`()
@Test fun `stream window served from db when stream covers it`() // streamConnectedSince ≤ coverage.end → 소스 호출 0
@Test fun `stream gap forces fetch`()
@Test fun `api returns decimal strings ascending`(); @Test fun `api rejects count 201`(); @Test fun `api unknown market 404`()
```

- [ ] **Step 2: 실패 확인** — `./gradlew :services:marketdata-service:test` → FAIL.
- [ ] **Step 3: `CandleSync` 구현** — `syncWindow`: `to`부터 `count=200` 과거 방향 페이지(응답 200개이고 가장 오래된 start ≥ from이면 그 시각을 다음 `to`로). `[from,to)`의 소스 집합과 DB 집합을 비교해 값이 다르거나 없는 것은 upsert, DB에만 있는 것은 delete, 이어서 `coverage.add(from,to)`. 변경이 있으면 `sink.onCorrected`, `audit`이면 `CorrectionLog`. `syncBefore`: 한 페이지, 구간 `[가장 오래된 start, to)`(200개 미만이면 `[EPOCH, to)`), 같은 비교 규칙.
- [ ] **Step 4: `CandleHistoryService.page` 구현**

```
end = to ?: unit.floor(now); live = if (to == null) live.current(i,u) else null
cov = coverage.end(i,u); since = live.streamConnectedSince(i.source)
if (to == null && cov != null && since != null && since <= cov && end > cov) { result += candles.findRange(i,u,cov,end); cursor = cov } else cursor = end
while (result.size < count) {
  start = coverage.coveredStartFor(i,u,cursor) ?: run { sync.syncBefore(i,u,cursor,USER); coverage.coveredStartFor(i,u,cursor)!! }
  result = candles.findBefore(i,u,cursor,count - result.size).filter { it.start >= start } + result
  if (start == Instant.EPOCH) { hasMore = false; break }
  if (result.size < count) cursor = start
}
```
- [ ] **Step 5: 컨트롤러 구현** — 위 형태 그대로, `ContractJson`으로 직렬화.
- [ ] **Step 6: 통과 확인** → PASS.
- [ ] **Step 7: Commit** — `git commit -m "feat(marketdata): storage, coverage, window sync and history API"`

---

### Task 7: marketdata — 이벤트 수신, 진행 중 캔들, 1초 발행, 종목 API

**Files:**
- Create: `ingest/{EventsController,Handlers}.kt`, `live/{LiveCandleBook,TickerCache,StreamStatusTracker,LiveIngest,LivePublisher,ViewingTracker}.kt`, `api/MarketController.kt`, `sync/Startup.kt`
- Delete: `candle/NoLiveView.kt`, `candle/NoopChangeSink.kt`
- Test: `live/LiveCandleBookTest.kt`, `live/LiveIngestTest.kt`, `live/LivePublisherTest.kt`, `ingest/EventsControllerTest.kt`, `api/MarketControllerTest.kt`, `sync/StartupTest.kt`

**Interfaces:**
- Consumes: Task 3 `EventInbox`·`EventHandler`·`EventPublisher`, Task 6 전부
- Produces:
  ```kotlin
  fun interface ClosedCandleSink { fun onClosed(authoritative: Candle) }
  data class LiveUpdate(val candle: Candle?, val instrument: InstrumentId, val unit: CandleUnit, val start: Instant, val state: CandleState)
  class LiveCandleBook(clock: Clock, closeGrace: Duration, sink: ClosedCandleSink) {
    fun seedClosedDays(i: InstrumentId, days: List<Candle>)   // 이번 달(또는 더 이른 이번 주) 시작 ~ 오늘 전의 마감 일봉
    fun onCandle(e: SourceCandleEvent); fun onTicker(t: TickerSnapshot); fun onTrade(e: TradeEvent)
    fun sweep(now: Instant); fun current(i: InstrumentId, u: CandleUnit): Candle?; fun drainUpdates(): List<LiveUpdate>
  }
  class TickerCache { fun put(t: TickerSnapshot); fun all(): List<TickerSnapshot>; fun amount24h(i: InstrumentId): BigDecimal?; fun drainChanged(): List<TickerSnapshot> }
  class StreamStatusTracker { fun update(source: String, status: StreamStatus); fun connectedSince(source: String): Instant?; fun onConnected(l: (source: String, at: Instant) -> Unit) }
  class LiveIngest(book, tickers, candles: CandleRepository, coverage: CoverageRepository, tracker) : LiveView, ClosedCandleSink
    // onClosed: 그 start가 coverage에 포함되면 저장하지 않음, 아니면 upsert
  class LivePublisher(book, tickers, publisher: EventPublisher, clock) : CandleChangeSink {
    @Scheduled(fixedRate = 1000) fun tick()  // sweep(now) → drainUpdates + 쌓인 정정 → 각각 chart.candle-update.v1(payload CandleUpdateEvent) 발행, 바뀐 티커 → chart.ticker-update.v1(payload TickerSnapshot)
  }
  class ViewingTracker(clock) { fun update(e: ViewingEvent); fun viewed(): Set<InstrumentId> }   // 30초 지난 gateway 보고는 버림
  // 핸들러: market.trade → book.onTrade / market.candle → book.onCandle / market.ticker → book.onTicker + tickers.put
  //        market.stream-status → tracker.update / market.instrument → InstrumentRepository.sync / realtime.viewing → viewing.update
  // POST /internal/events → EventInbox.accept → 202
  // GET /api/markets → [{"market","koreanName","englishName","price","changeRate","amount24h"}] (active만, amount24h 내림차순, 숫자 문자열)
  class Startup(source, instruments, tickers, book, tracker, sync: CandleSync, candles: CandleRepository, retry: Duration = 5s)
    // jobs.enabled일 때: 종목·티커·스트림 상태를 소스에서 받음(소스가 없으면 재시도).
    // 종목마다 DAY 구간 [min(이번 달 시작, 이번 주 시작), 오늘 시작)을 syncWindow(RECOVERY)로 맞춘 뒤 book.seedClosedDays
  ```

진행 중 캔들 규칙(테스트가 고정한다):
- 분 단위(M1~M240)는 (종목, 단위)마다 `display`와 `authoritative`(마지막 소스 분봉)를 따로 든다.
- **체결**: 종목별 최근 `sequential_id`(최근 1만 개)에 이미 있으면 무시. `b = unit.floor(tradedAt)`. `b < display.start` → 무시. 같으면 close=price, high=max, low=min, volume+=v, amount+=price×v. 크면 현재 캔들 마감 후 새 캔들.
- **분봉 권위 값**(같은 start): `authoritative = 값`. `lastTradeAt ≥ 마지막 반영 체결 시각`이면 `display = 권위 값 그대로`(업비트가 이김, 우리 값이 더 커도). 더 오래된 이벤트면 high=max, low=min, volume=max, amount=max, close 유지. 새 start면 이전 캔들 마감 후 시작.
- **일봉**: 티커가 올 때마다 `display = authoritative = 티커의 당일 일봉`. 체결은 일봉에 누적하지 않는다.
- **주·월봉**: 티커가 올 때마다 `display = 합치기(그 기간의 마감 일봉들 + 티커 당일 일봉)` — 시가 = 첫 일봉 시가, 고가 = max, 저가 = min, 종가 = 티커 종가, 거래량·거래대금 = 합. 일봉이 마감되면 마감 일봉 목록에 더하고, 주·월이 바뀌면 목록을 비운다. 체결은 주·월봉에 누적하지 않는다.
- **마감**: `sink.onClosed(authoritative ?: display)` 후 `LiveUpdate(CLOSED)`. 마감된 start에 대한 늦은 체결은 무시, 늦은 권위 값은 `sink.onClosed`만 다시 부른다.
- 같은 권위 값이 두 번 오면 변화 없음.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun `trade updates every unit`()                       // M1·M60·DAY·WEEK 현재 캔들 close 모두 갱신
@Test fun `older trade is ignored`()
@Test fun `next bucket trade closes previous with authoritative`()
@Test fun `newer authoritative wins even if smaller`()        // 체결로 volume 2.0(같은 체결 두 번 들어온 상황 흉내), lastTradeAt 더 새로운 권위 volume 1.0 → display 1.0
@Test fun `stale authoritative never shrinks display`()       // 체결 10:00:05 반영 후 lastTradeAt 10:00:03 권위 high 100 → 체결로 만든 high 101 유지
@Test fun `repeated sequential id is ignored`()               // 재구독으로 같은 sequential_id 체결이 다시 옴 → volume 한 번만 (Review Focus 4)
@Test fun `week candle is closed days plus ticker day`()      // 마감 일봉 2개 + 티커 당일 → 시가·고가·저가·종가·거래량이 기대값과 같음
@Test fun `sweep closes illiquid candle after grace`()
@Test fun `late authoritative for closed bucket only persists`()   // Review Focus 3
@Test fun `week boundary`()                                  // 일요일 23:59:59.999Z → 이전 주, 월요일 00:00Z → 새 주
@Test fun `ticker drives day candle`()
@Test fun `closed candle in verified range is not persisted`()     // Review Focus 3
@Test fun `duplicate trade batch is applied once`()          // 같은 envelope 묶음을 POST 두 번 → volume 한 번만 (Review Focus 4)
@Test fun `publisher emits only changed and nothing without trades`()
@Test fun `corrections are published as corrected and removed`()
@Test fun `markets api orders by amount24h with string numbers`()
@Test fun `startup waits for source instead of failing`()    // 소스가 처음 2번 실패 → 세 번째에 성공, 앱은 계속 살아 있음 (Review Focus 5)
```

- [ ] **Step 2: 실패 확인** — `./gradlew :services:marketdata-service:test` → FAIL.
- [ ] **Step 3: 구현** — 임시 빈 둘을 지운다. `HttpEventTransport` 빈은 `anthill.events.subscribers`로 만든다. `anthill.jobs.enabled=false`면 `Startup`·스케줄러를 끈다.
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(marketdata): event ingest, live candle book and 1s publisher"`

---

### Task 8: marketdata — 정합성 검사, 복구, 과거 채우기, 대조 검사 도구

**Files:**
- Create: `sync/{ReconcilePlanner,Reconciler,Recovery,InitialBackfill}.kt`, `tools/VerifyCandlesRunner.kt`; Modify: `sync/Startup.kt`
- Test: `sync/ReconcilePlannerTest.kt`, `sync/ReconcilerTest.kt`, `sync/RecoveryTest.kt`, `sync/InitialBackfillTest.kt`, `tools/VerifyCandlesRunnerTest.kt`

**Interfaces:**
- Consumes: `CandleSync`, `CoverageRepository`, `InstrumentRepository`, `TickerCache`, `ViewingTracker`, `StreamStatusTracker.onConnected`, `SourceClient`
- Produces:
  ```kotlin
  data class ReconcileJob(val instrument: InstrumentId, val unit: CandleUnit, val from: Instant, val to: Instant)
  class ReconcilePlanner {
    // to = unit.floor(now), from = coverage end. due ⇔ to > from && now ≥ unit.next(from) + unit.reconcileSlack
    // 정렬: viewed → amount24h 내림차순 → CandleUnit 순서
    fun due(now: Instant, ends: Map<Pair<InstrumentId, CandleUnit>, Instant>, viewed: Set<InstrumentId>, amount24h: (InstrumentId) -> BigDecimal?): List<ReconcileJob>
  }
  class Reconciler(planner, sync, coverage, instruments, tickers, viewing, meter, clock) {
    @Scheduled(fixedDelay = 5000) fun runDue()   // paused면 아무것도 안 함. 각 job: syncWindow(…, RECONCILE, audit = true)
    fun pause(); fun resume()
    // 게이지: anthill.reconcile.overdue{unit}, anthill.reconcile.lag.seconds{unit}
  }
  data class Interval(val from: Instant, val to: Instant)
  class OutageLog(jdbc: JdbcClient) { fun open(source: String, from: Instant); fun close(source: String, to: Instant); fun windows(from: Instant, to: Instant): List<Interval> }
  class Recovery(instruments, coverage, sync, reconciler, backfill, outages: OutageLog, clock) {
    fun recoverAll(source: String)   // pause → 종목×단위: end 있으면 syncWindow(end, floor(now), RECOVERY, audit=true), 없으면 syncBefore(floor(now), RECOVERY) → resume → outages.close(source, now) → backfill.startAsync()
  }
  // 장애 구간: StreamStatusTracker가 끊김(connected=false)을 받으면 outages.open(source, at). marketdata 시작 시에는 마지막 coverage 끝을 from으로 연다.
  // anthill.reconcile.overdue 게이지는 마감 시각이 열린 장애 구간 또는 기록된 장애 구간 안에 있는 캔들을 세지 않는다.
  class InitialBackfill(instruments, coverage, sync, tickers, clock, minuteDays: Int) {
    fun startAsync()  // 진행 중이면 무시. 종목은 amount24h 내림차순. DAY·WEEK·MONTH: reachedHistoryStart까지 syncBefore 반복.
                      // M1~M240: syncWindow(floor(now − minuteDays일), coverage.start ?: floor(now), BACKFILL, audit=false). 끝나면 로그 "initial backfill complete"
  }
  data class VerifyReport(val checkedWindows: Int, val checkedCandles: Int, val mismatches: List<String>)
  @Profile("verify") class VerifyCandlesRunner(…) : ApplicationRunner {
    fun verify(samples: List<Pair<InstrumentId, CandleUnit>>, now: Instant): VerifyReport
    // 종목: 24h 거래대금 상위 5 + 하위 5 + 무작위 5, 단위 11종. to = min(coverage.end, floor(now − reconcileDeadline)) 이전 200개를 소스와 DB에서 비교. 불일치가 있으면 종료 코드 1.
    // 기록된 장애 구간 목록도 함께 출력한다 (불일치 판정은 장애 구간에도 적용).
  }
  ```
  배선: `StreamStatusTracker.onConnected { source, _ -> recovery.recoverAll(source) }`(가상 스레드, 같은 소스에 대해 동시에 두 번 돌지 않게). `Startup`은 마지막에 `recoverAll`을 한 번 부른다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun `1m job due 3 minutes after first unverified close`()  // end=10:00 → 10:03:59 not due, 10:04:00 due
@Test fun `10m job due half a period after close`()
@Test fun `viewed instruments first then amount desc`()
@Test fun `deadline holds at configured budget`()   // 299종목, 9회/초 시뮬레이션 1시간: 1·3·5분 캔들 모두 마감 후 5분 안에 검사
@Test fun `mismatch corrected logged and pushed`()
@Test fun `quiet market window with no candles extends coverage`()   // Review Focus 2
@Test fun `paused reconciler makes no calls`()
@Test fun `five minute gap is filled exactly`()      // coverage end = now−5분 → DB == FakeSource, end == floor(now)
@Test fun `adapter reconnect triggers recovery once`() // stream-status 연결 이벤트 두 번 빠르게 → recoverAll 한 번만 동시 실행 (Review Focus 5)
@Test fun `outage window is recorded and excluded from overdue`() // 끊김 10:00 → 재연결 10:05 → 복구 끝 10:09: outage [10:00,10:09], 그 사이 마감 캔들은 overdue에 안 셈
@Test fun `daily history stops at listing date`()
@Test fun `minute backfill covers exactly seven days`()
@Test fun `rerun makes no new calls`()
@Test fun `verify reports count and value mismatch but passes scale-only difference`()
```

- [ ] **Step 2: 실패 확인** — `./gradlew :services:marketdata-service:test` → FAIL.
- [ ] **Step 3: 구현** — 위 Interfaces 그대로. 실행: `./gradlew :services:marketdata-service:bootRun --args='--spring.profiles.active=verify'`(verify 프로필은 `anthill.jobs.enabled=false`).
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(marketdata): reconciliation, recovery, backfill and verify tool"`

---

### Task 9: edge-gateway — 라우팅, WebSocket 허브, viewing 발행

**Files:**
- Create: `realtime/{SubscriptionRegistry,StreamWebSocketHandler,GatewayFanout,ViewingPublisher,EventsController}.kt`, `config/{WebSocketConfig,GatewayConfig}.kt`
- Test: `realtime/StreamWebSocketHandlerTest.kt`, `realtime/ViewingPublisherTest.kt`, `RoutingTest.kt`

**Interfaces:**
- Consumes: `EventInbox`, `EventHandler`, `EventPublisher`, `CandleUpdateEvent`(`chart.candle-update.v1` payload), `TickerSnapshot`(`chart.ticker-update.v1` payload), `ViewingEvent`
- Produces:
  ```kotlin
  class SubscriptionRegistry { fun subscribe(session: String, i: InstrumentId, u: CandleUnit); fun unsubscribe(session: String, i: InstrumentId, u: CandleUnit)
    fun subscribeTickers(session: String); fun remove(session: String); fun sessionsFor(i: InstrumentId, u: CandleUnit): Set<String>
    fun tickerSessions(): Set<String>; fun viewed(): Set<InstrumentId> }
  class GatewayFanout(registry, sender: (session: String, json: String) -> Unit)
    // 받은 이벤트 묶음 하나를 세션별 메시지 하나로: {"type":"candles","items":[{"market","unit","state","time","candle"}]} / {"type":"tickers","items":[{"market","price","changeRate","amount24h"}]}
  class ViewingPublisher(registry, publisher, gatewayId: String = UUID 시작 시 생성) { @Scheduled(fixedRate = 10_000) fun publish() }
  // /ws: client → {"op":"subscribe","market":"UPBIT:KRW-BTC","unit":"1m"} | {"op":"unsubscribe",…} | {"op":"subscribeTickers"}
  // POST /internal/events: chart.candle-update.v1, chart.ticker-update.v1 핸들러 → GatewayFanout
  ```

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun `subscribed session receives candle after event post`()   // StandardWebSocketClient 구독 → POST /internal/events → 1건 수신, state=live
@Test fun `unsubscribed session receives nothing`()
@Test fun `one message per session per batch`()                    // 같은 묶음에 같은 키 3건 → 메시지 1건, items 3
@Test fun `ticker subscribers get tickers`()
@Test fun `viewing publisher sends current viewed set`()
@Test fun `api is routed to marketdata`()   // JDK HttpServer를 marketdata 자리에 띄우고 라우트 uri를 그 포트로 덮어씀 → GET /api/candles 응답 본문 그대로
```

- [ ] **Step 2: 실패 확인** — `./gradlew :services:edge-gateway:test` → FAIL.
- [ ] **Step 3: 구현** — Spring WebSocket(`TextWebSocketHandler`)과 Gateway Server MVC 라우트를 한 앱에 둔다. 세션 전송은 세션별 `ConcurrentWebSocketSessionDecorator`.
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(edge-gateway): api routing, websocket hub and viewing events"`

---

### Task 10: 프론트 골격·API 계층·시간과 갱신 규칙

**Files:**
- Create: `frontend/` (Vite react-ts), `frontend/vite.config.ts`, `src/api/{types,client,stream}.ts`, `src/chart/{time,merge}.ts`
- Test: `src/chart/time.test.ts`, `src/chart/merge.test.ts`, `src/api/stream.test.ts`

**Interfaces:**
- Consumes: Task 6·7·9의 REST·`/ws` 형태
- Produces:
  ```ts
  type CandleDto = { instrument: string; unit: UnitCode; time: string; open: string; high: string; low: string; close: string; volume: string; amount: string }
  type UnitCode = '1m'|'3m'|'5m'|'10m'|'15m'|'30m'|'60m'|'240m'|'day'|'week'|'month'
  type StreamCandleItem = { market: string; unit: UnitCode; state: 'live'|'closed'|'corrected'|'removed'; time: string; candle: CandleDto | null }
  type MarketDto = { market: string; koreanName: string; englishName: string; price: string; changeRate: string; amount24h: string }
  function fetchCandles(market: string, unit: UnitCode, to?: string, count?: number): Promise<{ candles: CandleDto[]; live: CandleDto | null; hasMore: boolean }>
  function fetchMarkets(): Promise<MarketDto[]>
  class StreamClient { subscribe(market, unit); unsubscribe(market, unit); subscribeTickers(); onCandles(cb); onTickers(cb) } // 끊기면 1s→30s 재연결 후 구독 재전송
  function toChartTime(isoUtc: string): UTCTimestamp             // epoch초 + 9×3600 (화면 KST)
  function formatKst(isoUtc: string, unit: UnitCode): string     // 분봉 'YYYY-MM-DD HH:mm', 일·주·월 'YYYY-MM-DD'
  type Bar = { time: UTCTimestamp; open: number; high: number; low: number; close: number; volume: number; raw: CandleDto }
  function applyUpdate(bars: Bar[], item: StreamCandleItem): { bars: Bar[]; mode: 'update' | 'reset' }
    // live·closed이고 time ≥ 마지막 → 교체 또는 추가('update'), corrected·removed 또는 time < 마지막 → 교체·삭제('reset')
  ```

- [ ] **Step 1: 생성** — `node -v`가 v26인지 확인 후 `npm create vite@latest frontend -- --template react-ts && cd frontend && npm i react@19.3.1 react-dom@19.3.1 lightweight-charts@5.2.1 && npm i -D typescript@7.0.2 vitest@5.1.0`. `vite.config.ts`에 `/api`, `/ws`를 `localhost:8080`으로 프록시(`ws: true`).
- [ ] **Step 2: 실패하는 테스트 작성**

```ts
test('day candle 00Z shows as 09:00 KST date', () => expect(formatKst('2026-11-05T00:00:00Z','day')).toBe('2026-11-05'))
test('toChartTime adds 9h', () => expect(toChartTime('2026-11-05T00:00:00Z')).toBe(Date.UTC(2026,10,5,9)/1000))
test('live update replaces last bar', …); test('closed then next live appends', …)
test('corrected older bar resets', …); test('removed bar is dropped', …)
test('stream resubscribes after reconnect', …)   // 가짜 WebSocket 주입
```

- [ ] **Step 3: 실패 확인** — `cd frontend && npx vitest run` → FAIL.
- [ ] **Step 4: 구현** — 문자열→number 변환은 `Bar`를 만들 때만. 화면 숫자 표시는 항상 `raw` 문자열.
- [ ] **Step 5: 통과 확인** → PASS. `npx tsc --noEmit`도 통과(TS 7이 다른 도구와 충돌하면 6.0.3으로 내리고 기록).
- [ ] **Step 6: Commit** — `git commit -m "feat(frontend): api layer, time and merge rules"`

---

### Task 11: 차트·종목 목록 화면

**Files:**
- Create: `src/chart/{CandleChart,Legend}.tsx`, `src/markets/MarketList.tsx`; Modify: `src/App.tsx`, `src/index.css`

**Interfaces:**
- Consumes: Task 10 전부
- Produces: `<MarketList selected onSelect />`, `<CandleChart market unit />`, 단위 버튼 11개.

- [ ] **Step 1: `CandleChart`** — `createChart` + `addSeries(CandlestickSeries)` + `addSeries(HistogramSeries, { priceScaleId: '', priceFormat: { type: 'volume' } })`(아래 20%). 진입 시 `fetchCandles(count=200)` → `setData` + `live`면 `update`. `stream.subscribe`, `applyUpdate` 결과대로 `update`/`setData`. `subscribeVisibleLogicalRangeChange`에서 `from < 20 && hasMore && !loading`이면 `to = 첫 bar의 raw.time`으로 이전 200개를 앞에 붙임. 언마운트 시 구독 해제.
- [ ] **Step 2: `Legend`** — `subscribeCrosshairMove`의 time으로 bar를 찾아 `formatKst`와 `raw`의 문자열을 그대로 표시. 십자선이 없으면 마지막 bar.
- [ ] **Step 3: `MarketList`** — `fetchMarkets()` + `subscribeTickers()`, 한글명·코드 검색, 선택. 기본 `UPBIT:KRW-BTC`, `1m`.
- [ ] **Step 4: 빌드 확인** — `npm run build` 오류 없음, `npx vitest run` PASS.
- [ ] **Step 5: 화면 확인** — 세 서비스 + `npm run dev`로 KRW-BTC 1m 캔들이 1초 간격으로 움직이는지, 단위 전환·과거 스크롤·검색이 되는지 확인. 업비트 웹 차트를 옆에 띄워 같은 분의 값 비교.
- [ ] **Step 6: Commit** — `git commit -m "feat(frontend): chart and market list UI"`

---

### Task 12: 함께 실행하기와 완료 기준 검증 (spec 8절)

**Files:**
- Create: `docker-compose.full.yml`, `README.md`(실행 방법), `scripts/compare-live.mjs`
- Modify: `docs/superpowers/specs/2026-11-04-phase1-chart-design.md` (11절 열린 항목에 결과 기록)

- [ ] **Step 1: 이미지와 전체 실행 파일** — `./gradlew :services:upbit-adapter:bootBuildImage :services:marketdata-service:bootBuildImage :services:edge-gateway:bootBuildImage`(이미지 `anthill/<서비스>:dev`). `docker-compose.full.yml`: `db` + 세 서비스, 서로의 주소는 서비스 이름으로 환경 변수 덮어쓰기(`SPRING_DATASOURCE_URL`, `ANTHILL_SOURCES_UPBIT`, `ANTHILL_EVENTS_SUBSCRIBERS_0_URL`, gateway 라우트 uri). `README.md`에 로컬 실행 순서(DB → 서비스 3개 `bootRun` → `npm run dev`)를 적는다.
- [ ] **Step 2: 24시간 수집** — 상시 실행 환경에서 `docker compose -f docker-compose.full.yml up -d`(어댑터는 dev 프로필). marketdata 로그에 `initial backfill complete`.
- [ ] **Step 3: 일치 검사(8-1, 8-2, 8-6)** — 24시간 뒤 verify 프로필 실행 → `mismatches=0`, 출력된 장애 구간 목록 확인. `curl -s localhost:8081/actuator/metrics/anthill.reconcile.overdue` → 모든 단위 0(장애 구간 제외). `anthill.events.dropped` 0.
- [ ] **Step 4: 끊김 복구(8-3)** — `curl -X POST 'localhost:8082/admin/stream/pause?seconds=300'`. 재연결 후 10분 안에 `anthill.reconcile.lag.seconds{unit=1m}` < 300, verify → 0.
- [ ] **Step 5: 서비스 재시작(8-4)** — marketdata, upbit-adapter, gateway를 각각 2분씩 내렸다 올린다(순서를 바꿔 한 번 더). 매번 10분 안에 verify → 0, 화면이 다시 1초 갱신을 받는다.
- [ ] **Step 6: 실시간(8-5)** — `scripts/compare-live.mjs`(node 26 내장 WebSocket)를 만든다. 업비트 `candle.1m`과 우리 `ws://localhost:8080/ws`(같은 종목 1m 구독)를 동시에 10분 기록한다(거래 많은 종목 3개 + 적은 종목 2개). 판정:
  - 지연: 업비트 분봉 메시지마다 2초 안에 같은 (종목, start)에 대한 우리 푸시가 있다.
  - 마감 값: 우리 `closed` 푸시 값 = 그 분의 마지막 업비트 메시지 값 (값 기준 비교).
  - 중복 없음: 우리 `live` 푸시의 거래량이 그 분의 마지막 업비트 거래량을 넘은 적이 없다.
  실행: `node scripts/compare-live.mjs --minutes 10` → 세 항목 모두 0건 위반. 함께 브라우저 개발자도구로 `candles` 메시지 간격 1초, 체결 없는 초에는 메시지 없음, day·week·month 진행 중 캔들이 움직이는지 확인.
- [ ] **Step 7: 과거 스크롤(8-7)** — 8일 이전까지 스크롤해 그려지는지 확인 후 verify(해당 구간 포함) → 0.
- [ ] **Step 8: 빈 시간 표시(spec 11절)** — 거래가 드문 종목의 1m 차트를 업비트 웹 차트와 나란히 비교. 결과를 spec 11절에 기록하고, 다르면 새 작업으로 보고.
- [ ] **Step 9: Commit** — `git commit -m "docs: phase 1 run guide and acceptance results"`
