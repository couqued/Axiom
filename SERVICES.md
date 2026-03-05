# Axiom 서비스 기술 문서

> 각 서비스의 구조, 설정, 소스 코드 상세 설명.
> README.md의 개요와 별개로 개발 참고용 문서입니다.

---

## 목차

1. [전체 아키텍처 요약](#1-전체-아키텍처-요약)
2. [api-gateway](#2-api-gateway)
3. [market-service](#3-market-service)
4. [order-service](#4-order-service)
5. [portfolio-service](#5-portfolio-service)
6. [strategy-service](#6-strategy-service)
7. [서비스 간 통신 정리](#7-서비스-간-통신-정리)
8. [공통 패턴 정리](#8-공통-패턴-정리)

---

## 1. 전체 아키텍처 요약

### 서버 구성

| 서비스 | 포트 | 내장 서버 | DB | Kafka |
|--------|------|-----------|----|-------|
| api-gateway | 8080 | Netty (비동기) | 없음 | 없음 |
| market-service | 8081 | Tomcat | market 스키마 | 없음 |
| order-service | 8082 | Tomcat | orders 스키마 | Producer |
| portfolio-service | 8083 | Tomcat | portfolio 스키마 | Consumer |
| strategy-service | 8084 | Tomcat | 없음 | 없음 |
| frontend (Vite dev) | 5173 | Node.js | 없음 | 없음 |

> api-gateway만 Netty인 이유: Spring Cloud Gateway는 내부적으로 Spring WebFlux(리액티브) 기반이라 Tomcat 대신 Netty가 자동 사용됨.

### 클라이언트 진입점

클라이언트(브라우저/앱)는 **api-gateway(8080)만 호출**합니다. 내부 서비스(8081~8084)는 클라이언트에 노출되지 않습니다.

### KIS Access Token 중앙화

KIS는 App Key당 동시 토큰 발급을 제한(403 오류)하므로:
- **market-service** 에서만 KIS에 직접 토큰 발급 (`/oauth2/tokenP`)
- **order-service, portfolio-service** 는 `GET http://localhost:8081/internal/token` 으로 위임 조회
- 토큰은 각 서비스 메모리에 24시간 캐싱, 만료 30분 전 갱신

---

## 2. api-gateway

### 역할
- 클라이언트의 **단일 진입점** (포트 8080)
- 경로 기반 라우팅 → 각 마이크로서비스로 전달
- 전역 CORS 처리

### 파일 구조

```
api-gateway/
├── build.gradle
└── src/main/
    ├── java/com/axiom/gateway/
    │   ├── GatewayApplication.java   ← 진입점
    │   └── notification/
    │       └── ServiceLifecycleNotifier.java  ← 서비스 기동/종료 Slack 알림
    └── resources/
        ├── application.yml           ← 모든 설정 + slack 플레이스홀더
        └── application-secret.yml    ← Slack Webhook URL (gitignore)
```

라우팅 로직 전부가 `application.yml` 설정으로 동작합니다. `ServiceLifecycleNotifier`는 api-gateway에 Lombok이 없으므로 `LoggerFactory.getLogger()`를 사용합니다.

### `build.gradle`

```groovy
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway' // 핵심 라우팅 엔진
    implementation 'org.springframework.boot:spring-boot-starter-actuator'  // /actuator/health
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:2024.0.0"
        // Spring Cloud 라이브러리 버전 일괄 관리 (직접 버전 명시 불필요)
    }
}
```

### `application.yml`

```yaml
server:
  port: 8080

spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"   # 모든 출처 허용 (개발용)
            allowedMethods: [GET, POST, PUT, DELETE, OPTIONS]
            allowedHeaders: "*"
            allowCredentials: true
      routes:
        - id: market-service
          uri: http://localhost:8081
          predicates:
            - Path=/api/market/**
          filters:
            - RewritePath=/api/market/(?<segment>.*), /api/${segment}
            # /api/market/stocks/... → /api/stocks/... 로 변환

        - id: order-service
          uri: http://localhost:8082
          predicates:
            - Path=/api/orders/**        # 경로 그대로 전달

        - id: portfolio-service
          uri: http://localhost:8083
          predicates:
            - Path=/api/portfolio/**

        - id: strategy-service
          uri: http://localhost:8084
          predicates:
            - Path=/api/strategy/**
```

#### RewritePath 필터가 market-service에만 있는 이유

```
클라이언트 요청:  GET /api/market/stocks/005930/price
                          ↓ RewritePath 적용
market-service:  GET /api/stocks/005930/price
```

market-service 컨트롤러가 `/api/stocks/**` 로 매핑되어 있기 때문에 `/api/market/` prefix를 제거해야 합니다.

---

## 3. market-service

### 역할
- 종목 검색, 현재가 조회
- 일봉(OHLCV) 데이터 수집 및 DB 저장 (매일 15:40)
- KIS Access Token 중앙 발급 및 타 서비스 위임 제공

### 파일 구조

```
market-service/
├── build.gradle
└── src/main/
    ├── java/com/axiom/market/
    │   ├── MarketApplication.java
    │   ├── config/
    │   │   ├── KisApiConfig.java          ← KIS 모드 설정 + kisWebClient Bean
    │   │   └── CandleConfig.java          ← 일봉 수집 설정 (watch-tickers)
    │   ├── controller/
    │   │   ├── StockController.java       ← GET /api/stocks/**
    │   │   ├── InternalTokenController.java ← GET /internal/token (내부용)
    │   │   └── InternalMarketController.java ← GET /internal/screened-tickers (신규)
    │   ├── service/
    │   │   ├── KisTokenService.java       ← KIS 토큰 발급/캐싱
    │   │   ├── KisMarketApiService.java   ← 현재가 조회 (mock/paper/real, mrkt_warn_cls_code 포함)
    │   │   ├── StockSearchService.java    ← 종목 검색 (mock 하드코딩)
    │   │   ├── CandleService.java         ← 일봉 조회/수집/저장
    │   │   ├── IndexCandleService.java    ← 지수 일봉 조회 (코스피/코스닥)
    │   │   └── StockScreenerService.java  ← 코스피200+코스닥150 유니버스 로드 및 캐싱 (신규)
    │   ├── entity/DailyCandle.java        ← market.daily_candles 엔티티
    │   ├── repository/DailyCandleRepository.java
    │   ├── scheduler/CandleCollectScheduler.java ← 평일 15:40 자동 수집
    │   ├── notification/
    │   │   └── ServiceLifecycleNotifier.java ← 서비스 기동/종료 Slack 알림
    │   └── dto/
    │       ├── StockPriceDto.java         ← marketWarnCode + isSafe() 포함 (신규 필드)
    │       ├── StockInfoDto.java
    │       ├── CandleDto.java
    │       └── StockUniverse.java         ← stock-universe.json 역직렬화 DTO (신규)
    └── resources/
        ├── application.yml
        ├── stock-universe.json            ← 코스피200+코스닥150 종목 코드 목록 (신규)
        └── application-secret.yml         ← KIS API 키 (gitignore)
```

### `build.gradle`

```groovy
dependencies {
    implementation 'spring-boot-starter-web'      // REST API, Tomcat
    implementation 'spring-boot-starter-webflux'  // WebClient (KIS API 호출)
    implementation 'spring-boot-starter-data-jpa' // JPA (daily_candles 저장)
    implementation 'spring-boot-starter-actuator'
    runtimeOnly    'postgresql'
    compileOnly    'lombok'
}
```

### `application.yml`

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/axiom?currentSchema=market
  jpa:
    hibernate:
      ddl-auto: update  # 기동 시 엔티티 기반으로 테이블 자동 생성/변경

kis:
  mode: paper           # mock | paper | real
  paper:
    base-url: https://openapivts.koreainvestment.com:29443  # 모의투자
  real:
    base-url: https://openapi.koreainvestment.com:9443       # 실계좌

candle:
  watch-tickers:        # 일봉 자동 수집 대상 종목
    - "005930"          # 삼성전자
    - "000660"          # SK하이닉스
    - "035420"          # NAVER
    - "051910"          # LG화학
    - "006400"          # 삼성SDI
  default-days: 60
```

### `MarketApplication.java`

```java
@ConfigurationPropertiesScan  // KisApiConfig, CandleConfig 자동 Bean 등록
@EnableScheduling             // CandleCollectScheduler 활성화
```

### `KisApiConfig.java`

```java
@ConfigurationProperties(prefix = "kis")
public class KisApiConfig {
    private String mode;        // "mock" | "paper" | "real"
    private ModeConfig paper;
    private ModeConfig real;

    public ModeConfig getActive() {
        return "real".equals(mode) ? real : paper;
    }
    public boolean isMock()  { return "mock".equals(mode); }
    public boolean isPaper() { return "paper".equals(mode); }

    @Bean
    public WebClient kisWebClient() {
        // mock이면 더미 URL, 아니면 KIS 실제 서버 URL
    }
}
```

### `KisTokenService.java`

```
getAccessToken()
  → needsRefresh()? (null이거나 만료 30분 전)
      Yes → refreshToken(): KIS /oauth2/tokenP POST, 최대 3회 재시도
      No  → cachedToken 그대로 반환

- synchronized: 동시 호출 시 중복 발급 방지
- 토큰 유효기간: 24시간 (만료 30분 전 자동 갱신)
```

### `InternalTokenController.java`

```java
GET /internal/token
  → KisTokenService.getAccessToken() 반환
  → order-service, portfolio-service 전용 내부 API
  → api-gateway 라우팅 없음 (외부 노출 안 됨)
```

### `KisMarketApiService.java`

```
getCurrentPrice(ticker)
  mock  → 종목별 기준가 ± 랜덤 변동 (최대 ±10,000원), marketWarnCode="00" 고정
  paper/real → KIS inquire-price API 호출
               TR: FHKST01010100
               반환: 현재가, 등락, 고/저/시가, 거래량, mrkt_warn_cls_code
               mrkt_warn_cls_code → StockPriceDto.marketWarnCode 매핑
```

### `StockPriceDto.java` (market-service)

```java
private String marketWarnCode;  // "00"=정상, "01"=투자주의, "02"=투자경고, "03"=투자위험

public boolean isSafe() {
    return marketWarnCode == null || "00".equals(marketWarnCode);
}
// strategy-service에서 BUY 신호 시 isSafe() = false 이면 매수 스킵
```

### `StockScreenerService.java` (신규)

```
@PostConstruct: 서비스 기동 시 stock-universe.json 로드
@Scheduled(cron = "0 20 8 * * MON-FRI"): 매일 08:20 갱신

refresh():
  ClassPathResource("stock-universe.json") 읽기
    → StockUniverse 역직렬화
    → kospi200 + kosdaq150 병합 → List<String>
    → cachedTickers (volatile) 갱신

getScreenedTickers() → cachedTickers 반환
  → InternalMarketController를 통해 strategy-service가 주기적으로 조회
```

### `InternalMarketController.java` (신규)

```java
GET /internal/screened-tickers
  → StockScreenerService.getScreenedTickers() 반환
  → api-gateway 라우팅 없음 (외부 노출 안 됨)
  → strategy-service 전용 내부 API
```

### `StockSearchService.java`

14개 주요 종목 하드코딩 목록에서 검색합니다. 종목명/ticker/섹터로 검색 가능합니다. KIS 종목 검색 API 연동 전까지 사용하는 임시 구현입니다.

### `CandleService.java`

```
getCandles(ticker, days)
  mock → 랜덤 캔들 생성 (주말 제외, ticker 해시 기반 고정 시드로 일관성 유지)
  paper/real:
    ① DB 조회 (market.daily_candles, from ~ today)
    ② 마지막 수집일 이후 데이터 없으면 KIS API 보완 수집
       TR: FHKST03010100 (inquire-daily-itemchartprice)
    ③ 중복 날짜 필터링 후 saveAll()
    ④ 최근 days개만 반환

collectCandle(ticker, date) — 스케줄러 전용
  → 해당 날짜 이미 수집됐으면 스킵
  → 없으면 KIS API 호출 후 저장
```

### `DailyCandle.java` (Entity)

```java
@Table(schema = "market", uniqueConstraints = {"ticker", "trade_date"})
// (ticker, trade_date) 복합 유니크 → 같은 날짜 중복 저장 방지
```

### `CandleCollectScheduler.java`

```java
@Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
// 매일 평일 15:40 KST (장 마감 15:30 이후)
// mock 모드이면 실행하지 않음
```

### API 엔드포인트

| Method | 경로 (서비스 내부) | 게이트웨이 경로 | 설명 |
|--------|------------------|----------------|------|
| GET | `/api/stocks/{ticker}/price` | `/api/market/stocks/{ticker}/price` | 현재가 |
| GET | `/api/stocks/search?query=` | `/api/market/stocks/search?query=` | 종목 검색 |
| GET | `/api/stocks/{ticker}` | `/api/market/stocks/{ticker}` | 종목 상세 |
| GET | `/api/stocks/{ticker}/candles?days=60` | `/api/market/stocks/{ticker}/candles` | 일봉 조회 |
| GET | `/api/index/{code}/candles?days=N` | 없음 (내부 전용) | 지수 일봉 조회 (strategy-service 전용) |
| GET | `/internal/token` | 없음 (내부 전용) | KIS 토큰 위임 |
| GET | `/internal/screened-tickers` | 없음 (내부 전용) | 코스피200+코스닥150 감시 종목 목록 (strategy-service 전용) |

---

## 4. order-service

### 역할
- 매수/매도 주문 처리 및 DB 저장
- 주식 시장 운영시간 체크 (KST 평일 09:00~15:30)
- 주문 체결 후 Kafka 이벤트 발행 → portfolio-service 자동 갱신 트리거

### 파일 구조

```
order-service/
├── build.gradle
└── src/main/
    ├── java/com/axiom/order/
    │   ├── OrderApplication.java
    │   ├── config/KisApiConfig.java           ← KIS 설정 + kisWebClient Bean
    │   ├── controller/OrderController.java    ← POST /api/orders/buy|sell, GET /api/orders
    │   ├── service/
    │   │   ├── OrderService.java              ← 주문 처리 핵심 로직
    │   │   ├── KisOrderApiService.java        ← KIS 주문 API 호출
    │   │   └── KisTokenService.java           ← 토큰 위임 조회 (market-service)
    │   ├── util/MarketHoursChecker.java       ← 운영시간 체크
    │   ├── entity/TradeOrder.java             ← orders.trade_orders 엔티티
    │   ├── repository/TradeOrderRepository.java
    │   ├── kafka/OrderEventProducer.java      ← Kafka 이벤트 발행
    │   ├── notification/
    │   │   └── ServiceLifecycleNotifier.java  ← 서비스 기동/종료 Slack 알림
    │   └── dto/
    │       ├── OrderRequest.java
    │       └── OrderResponse.java
    └── resources/
        ├── application.yml
        └── application-secret.yml             ← KIS API 키 + Slack Webhook URL (gitignore)
```

### `build.gradle`

```groovy
dependencies {
    implementation 'spring-boot-starter-web'
    implementation 'spring-boot-starter-webflux'  // WebClient
    implementation 'spring-boot-starter-data-jpa'
    implementation 'spring-kafka'                 // Kafka Producer
    runtimeOnly    'postgresql'
    compileOnly    'lombok'
}
```

### `application.yml`

```yaml
server:
  port: 8082

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/axiom?currentSchema=orders
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer:   StringSerializer
      value-serializer: JsonSerializer    # 이벤트를 JSON으로 직렬화

kis:
  mode: paper

market-service:
  url: http://localhost:8081              # KIS 토큰 위임 조회 주소
```

### `OrderController.java`

```java
POST /api/orders/buy
  ① MarketHoursChecker.isMarketOpen() 체크
     → false: 400 MARKET_CLOSED 반환 (다음 개장 시각 포함)
  ② request.setOrderType(BUY) 자동 설정  ← 클라이언트 실수 방지
  ③ orderService.placeOrder(request) 실행

POST /api/orders/sell → 동일 (SELL 자동 설정)
GET  /api/orders      → 전체 주문 내역 (최신순)
GET  /api/orders/ticker/{ticker} → 종목별 주문 내역
```

**운영시간 외 응답:**
```json
{
  "error":        "MARKET_CLOSED",
  "message":      "현재 주식 시장 운영 시간이 아닙니다. (평일 09:00~15:30 KST)",
  "marketOpenAt": "2026-03-04T09:00:00+09:00"
}
```

### `OrderService.java`

```
placeOrder(request)
  ① price × quantity = totalAmount 계산
  ② TradeOrder(status=PENDING) 생성 → DB 저장 (이력 보장)
  ③ KisOrderApiService.placeOrder() 호출
       성공 → status=FILLED, filledAt 설정 → DB 업데이트
               → OrderEventProducer.publishOrderFilled() (Kafka 발행)
       실패 → status=FAILED → DB 저장
               → RuntimeException throw (클라이언트에 오류 반환)
  ④ OrderResponse 반환
```

### `KisOrderApiService.java`

**TR ID 자동 선택 (모의/실계좌 구분):**

```java
String trId = isPaper()
    ? (isBuy ? "VTTC0802U" : "VTTC0801U")   // V 접두사 = 모의투자
    : (isBuy ? "TTTC0802U" : "TTTC0801U");  // T 접두사 = 실계좌
```

**계좌번호 분리:**
```java
"50171018-01".split("-") → CANO="50171018", ACNT_PRDT_CD="01"
```

**응답 검증:**
```java
if (!"0".equals(response.get("rt_cd"))) {
    throw new RuntimeException("KIS 주문 거부: " + msg);
}
// rt_cd=0 성공, 그 외 실패
```

### `KisTokenService.java` (order-service)

```java
// KIS에 직접 발급하지 않고 market-service에 위임
GET http://localhost:8081/internal/token → { "token": "Bearer eyJ..." }
// 수신한 토큰은 로컬에 24시간 캐싱
```

### `MarketHoursChecker.java`

```java
isMarketOpen()
  mock 모드 → 항상 true (24시간 테스트 가능)
  paper/real → KST 평일 09:00~15:30 여부 확인

nextMarketOpenAt()
  → 다음 평일 09:00 시각을 ISO-8601 KST 형식으로 반환
  → 주말 자동 건너뜀
```

### `TradeOrder.java` (Entity)

```java
@Table(name = "trade_orders", schema = "orders")
enum OrderType   { BUY, SELL }
enum OrderStatus { PENDING, FILLED, CANCELLED, FAILED }

@PrePersist
void prePersist() {
    this.createdAt = LocalDateTime.now();  // 저장 시 자동 설정
}

// @Enumerated(EnumType.STRING): enum을 숫자(0,1)가 아닌 문자열("BUY")로 저장
```

### `OrderEventProducer.java`

```java
kafkaTemplate.send("order-events", order.getTicker(), event);
//                         ↑ 파티션 키 = ticker
// 같은 종목 이벤트는 같은 파티션 → 순서 보장

// 발행 이벤트 구조:
{
  "eventType":   "ORDER_FILLED",
  "orderId":     1,
  "ticker":      "005930",
  "orderType":   "BUY",
  "quantity":    10,
  "price":       75000,
  "totalAmount": 750000,
  "filledAt":    "2026-03-03T10:30:00"
}
```

---

## 5. portfolio-service

### 역할
- 보유 주식 현황 관리 (Kafka 이벤트로 자동 갱신)
- KIS 계좌 잔고 조회

### 파일 구조

```
portfolio-service/
├── build.gradle
└── src/main/
    ├── java/com/axiom/portfolio/
    │   ├── PortfolioApplication.java
    │   ├── config/KisApiConfig.java              ← KIS 설정 + kisWebClient Bean
    │   ├── controller/PortfolioController.java   ← GET /api/portfolio, /balance
    │   ├── service/
    │   │   ├── PortfolioService.java             ← 포트폴리오 CRUD + 평균단가 계산
    │   │   ├── KisAccountApiService.java         ← KIS 잔고 조회
    │   │   └── KisTokenService.java              ← 토큰 위임 조회 (market-service)
    │   ├── kafka/OrderEventConsumer.java         ← Kafka 이벤트 소비
    │   ├── entity/Portfolio.java                 ← portfolio.portfolio 엔티티
    │   ├── repository/PortfolioRepository.java
    │   ├── notification/
    │   │   └── ServiceLifecycleNotifier.java     ← 서비스 기동/종료 Slack 알림
    │   └── dto/PortfolioItemDto.java
    └── resources/
        ├── application.yml
        └── application-secret.yml               ← KIS API 키 + Slack Webhook URL (gitignore)
```

### `build.gradle`

```groovy
dependencies {
    implementation 'spring-boot-starter-web'
    implementation 'spring-boot-starter-webflux'
    implementation 'spring-boot-starter-data-jpa'
    implementation 'spring-kafka'                // Kafka Consumer
    runtimeOnly    'postgresql'
    compileOnly    'lombok'
}
```

### `application.yml`

```yaml
server:
  port: 8083

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/axiom?currentSchema=portfolio
  kafka:
    consumer:
      group-id: portfolio-service   # Consumer 그룹 ID
      auto-offset-reset: earliest   # 처음 구독 시 가장 오래된 메시지부터
      value-deserializer: JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"  # 모든 패키지 JSON 역직렬화 허용

market-service:
  url: http://localhost:8081
```

**`group-id` 의미:** 같은 group-id의 Consumer는 같은 메시지를 중복으로 받지 않습니다. 인스턴스를 여러 개 띄워도 메시지가 한 번만 처리됩니다.

### `PortfolioController.java`

쓰기(매수/매도) API 없음. Kafka 이벤트로 자동 처리됩니다.

```java
GET /api/portfolio          → 보유 주식 목록 전체
GET /api/portfolio/balance  → KIS 계좌 잔고 조회
```

### `PortfolioService.java`

**매수 — `addPosition()`:**

```
기존 보유 종목? (findByTicker)
  Yes (추가 매수):
    newQuantity    = 기존 수량 + 신규 수량
    newTotalInvest = 기존 총투자금 + 신규 투자금
    newAvgPrice    = newTotalInvest / newQuantity  ← 평균단가 재계산
    DB 업데이트

  No (신규 종목):
    avgPrice    = 매수 단가
    totalInvest = 단가 × 수량
    DB 신규 저장
```

**평균단가 계산 예시:**
```
1차: 삼성전자 10주 × 75,000 = 750,000원
2차: 삼성전자  5주 × 80,000 = 400,000원
→ avgPrice = 1,150,000 / 15 = 76,667원
```

**매도 — `reducePosition()`:**

```
remaining = 기존 수량 - 매도 수량
  remaining <= 0 → 전량 매도: 레코드 삭제
  remaining > 0  → 부분 매도: 수량 차감, totalInvest 차감 (avgPrice 변경 없음)
```

### `OrderEventConsumer.java`

```java
@KafkaListener(topics = "order-events", groupId = "portfolio-service")
public void consume(Map<String, Object> event) {
    if (!"ORDER_FILLED".equals(event.get("eventType"))) return;

    if ("BUY".equals(orderType))  portfolioService.addPosition(...);
    if ("SELL".equals(orderType)) portfolioService.reducePosition(...);
}
// order-service와 직접 HTTP 통신 없음 → Kafka로 느슨하게 연결
```

### `KisAccountApiService.java`

```
getBalance()
  mock  → 고정값 반환 (총평가 1천만, 현금 5백만, 주식 5백만, 수익 25만)
  paper → KIS inquire-balance API (TR: VTTC8434R)
  real  → KIS inquire-balance API (TR: TTTC8434R)

반환 필드:
  totalBalance   ← tot_evlu_amt    (총 평가금액)
  cashBalance    ← dnca_tot_amt    (예수금)
  stockBalance   ← scts_evlu_amt   (주식 평가금액)
  profitLoss     ← evlu_pfls_smtl_amt (평가손익)
  profitLossRate ← evlu_erng_rt    (수익률 %)
```

### `Portfolio.java` (Entity)

```java
@Table(name = "portfolio", schema = "portfolio")
@Column(unique = true) String ticker;  // 종목당 1행 (추가 매수 시 업데이트)

@PrePersist @PreUpdate
void preUpdate() {
    this.updatedAt = LocalDateTime.now();  // 저장/수정 시 자동 갱신
}
```

`ticker` UNIQUE 이유: 포트폴리오는 "현재 보유 상태"를 나타냅니다. 거래 이력은 order-service의 `trade_orders` 테이블이 담당합니다.

---

## 6. strategy-service

### 역할
- 시장 상태(BULLISH/SIDEWAYS) 판별 후 적합한 전략 자동 선택 (하이브리드 전략 엔진)
- 자동매매 전략 실행 (5분 주기 스케줄러)
- 공통 리스크 관리 — 트레일링 스탑(고점 -7%) + 타임 컷(3거래일)
- 매매 신호 발생 시 order-service에 주문 위임
- Slack Incoming Webhook 알림

### 파일 구조

```
strategy-service/
├── build.gradle
└── src/main/
    ├── java/com/axiom/strategy/
    │   ├── StrategyApplication.java
    │   ├── config/StrategyConfig.java          ← 전략 설정 + WebClient 3개 Bean
    │   ├── admin/
    │   │   ├── AdminConfigStore.java           ← 런타임 설정 저장소 (paused + 투자 설정, JSON 영구 저장)
    │   │   ├── AdminController.java            ← GET/POST/PATCH /api/strategy/admin/**
    │   │   ├── AdminStatusDto.java
    │   │   └── AdminConfigDto.java
    │   ├── controller/StrategyController.java  ← POST /api/strategy/run, GET /api/strategy/market-state
    │   ├── strategy/
    │   │   ├── TradingStrategy.java            ← 전략 인터페이스
    │   │   ├── GoldenCrossStrategy.java        ← MA5/MA20 골든크로스 구현
    │   │   ├── VolatilityBreakoutStrategy.java ← 변동성 돌파 (상승장 단기)
    │   │   └── RsiBollingerStrategy.java       ← RSI+볼린저밴드 통합 (횡보장)
    │   ├── engine/StrategyEngine.java          ← 전략 실행 총괄 + 시장 상태 필터링
    │   ├── scheduler/
    │   │   ├── StrategyScheduler.java          ← 평일 09:05~15:20, 5분 주기
    │   │   ├── MarketStateScheduler.java       ← 평일 08:30, 시장 상태 갱신
    │   │   └── ForceExitScheduler.java         ← 평일 15:20, 변동성 돌파 강제 청산
    │   ├── service/
    │   │   ├── MarketStateService.java         ← 코스피 MA20 → BULLISH/SIDEWAYS 판별
    │   │   ├── TrailingStopService.java        ← 트레일링 스탑 (고점 -7%)
    │   │   └── TimeCutService.java             ← 타임 컷 (3거래일)
    │   ├── client/
    │   │   ├── MarketClient.java               ← market-service 캔들/현재가/지수 조회
    │   │   ├── OrderClient.java                ← 매수/매도 위임 (order-service)
    │   │   └── PortfolioClient.java            ← 보유 포지션 조회 (portfolio-service)
    │   ├── notification/
    │   │   ├── SlackNotifier.java              ← Slack Webhook 알림
    │   │   └── ServiceLifecycleNotifier.java   ← 서비스 기동/종료 Slack 알림 (SlackNotifier 위임)
    │   ├── util/TradingCalendar.java           ← 주말 제외 거래일 계산
    │   └── dto/
    │       ├── CandleDto.java
    │       ├── SignalDto.java                  ← BUY / SELL / HOLD
    │       ├── StockPriceDto.java              ← 현재가 (LiveCandle 생성용, marketWarnCode 포함)
    │       ├── PortfolioItemDto.java           ← 보유 포지션
    │       └── OrderRequest.java
    └── resources/
        ├── application.yml
        └── application-secret.yml             ← Slack Webhook URL (gitignore)
```

### `build.gradle`

```groovy
dependencies {
    implementation 'spring-boot-starter-web'
    implementation 'spring-boot-starter-webflux'  // WebClient
    implementation 'spring-boot-starter-actuator'
    compileOnly    'lombok'
    // JPA, Kafka, PostgreSQL 없음 — 자체 DB 없는 순수 비즈니스 로직 서비스
}
```

### `application.yml`

```yaml
server:
  port: 8084

strategy:
  watch-tickers:                        # yml fallback (08:30 이전 또는 market-service 응답 실패 시)
    - "005930"                          # 삼성전자
    - "000660"                          # SK하이닉스
  candle-days: 60                       # 캔들 조회 기간
  position-sizing:
    invest-amount-krw: 500000           # 1회 매수 금액(원). 수량 = floor(금액 / 현재가)
    max-positions: 3                    # 동시에 보유할 수 있는 최대 종목 수
  enabled-strategies:                   # 전체 등록 (시장 상태에 따라 런타임 필터링)
    - golden-cross
    - volatility-breakout
    - rsi-bollinger
  market-filter:
    enabled: true                       # false 시 모든 전략 항상 실행
    index-code: "0001"                  # 코스피
    ma-period: 20
  trailing-stop:
    enabled: true
    stop-percent: 7.0                   # 고점 대비 7% 하락 시 청산
  time-cut:
    enabled: true
    max-holding-days: 3                 # 3거래일 미반등 시 강제 청산
    applicable-strategies:
      - rsi-bollinger

market-service:
  url: http://localhost:8081
order-service:
  url: http://localhost:8082
portfolio-service:
  url: http://localhost:8083

slack:
  webhook-url: PLACEHOLDER   # application-secret.yml에서 실제 URL로 덮어씀
  enabled: false             # true 변경 시 실제 발송
```

### `AdminConfigStore.java`

런타임 관리자 설정을 메모리와 JSON 파일 두 곳에 이중 저장합니다.

```java
@Component
public class AdminConfigStore {
    private volatile boolean paused = false;          // 매매 중단 여부
    private volatile int investAmountKrw;             // 1회 매수 금액 (원)
    private volatile int maxPositions;                // 최대 동시 보유 종목 수

    @PostConstruct
    void init() {
        // 1) yml 기본값으로 초기화
        // 2) admin-config.json 존재 시 파일 값으로 덮어쓰기 (서비스 재시작 시 설정 복원)
    }

    // 설정 변경 시 admin-config.json에 자동 저장
    public void setPaused(boolean paused)  { this.paused = paused; saveToFile(); }
    public void setConfig(int investAmountKrw, int maxPositions) { ...; saveToFile(); }
}
```

- `volatile` 필드: 별도 락 없이 멀티스레드 환경에서 가시성 보장
- `admin-config.json` 위치: strategy-service 실행 디렉토리 (gitignore 적용)
- `paused=true` 상태에서 `StrategyEngine.run()` 즉시 스킵
- `ForceExitScheduler`는 `paused` 상태와 무관하게 항상 동작 (오버나이트 보호)

### `AdminController.java`

```java
GET  /api/strategy/admin/status  → AdminStatusDto { paused, investAmountKrw, maxPositions }
POST /api/strategy/admin/pause   → paused=true, 현재 상태 반환
POST /api/strategy/admin/resume  → paused=false, 현재 상태 반환
PATCH /api/strategy/admin/config → AdminConfigDto { investAmountKrw?, maxPositions? }
                                   null 필드는 기존 값 유지 (partial update)
```

### `TradingStrategy.java` — 전략 인터페이스

```java
public interface TradingStrategy {
    String getName();           // enabled-strategies 목록과 매칭
    int minimumCandles();       // 필요한 최소 캔들 수
    SignalDto evaluate(String ticker, List<CandleDto> candles);
}
```

새 전략 추가 방법:
1. `TradingStrategy` 구현 + `@Component` 추가
2. `getName()` 반환값을 `application.yml` `enabled-strategies`에 추가

`StrategyEngine` 코드 수정 없이 전략을 추가/제거할 수 있습니다.

### `GoldenCrossStrategy.java`

```
minimumCandles() = 21개  (MA20 = 20개 + 전일 비교용 1개)
전략 이름: "golden-cross" | 시장 상태: BULLISH | 보유 기간: 스윙 (며칠~몇 주)

신호 판단:
  골든크로스 → BUY:
    MA5_prev ≤ MA20_prev  AND  MA5_curr > MA20_curr
  데드크로스 → SELL:
    MA5_prev ≥ MA20_prev  AND  MA5_curr < MA20_curr
  둘 다 해당 없음 → HOLD
```

### `VolatilityBreakoutStrategy.java`

```
minimumCandles() = 3개
전략 이름: "volatility-breakout" | 시장 상태: BULLISH | 보유 기간: 당일 (오버나이트 X)

신호 판단:
  yesterday = candles[last-1]  (전일 캔들)
  today     = candles[last]    (당일 LiveCandle — 현재가)

  Range  = yesterday.high - yesterday.low
  목표가 = today.open + Range × 0.5

  현재가(today.close) ≥ 목표가 AND 당일 미매수 → BUY
  전략 자체는 SELL 없음 → ForceExitScheduler(15:20)가 강제 청산

todayBought: ConcurrentHashMap<String, LocalDate>
  매수 시 날짜 기록 → 당일 중복 매수 방지
```

### `RsiBollingerStrategy.java`

```
minimumCandles() = 21개  (BB_PERIOD=20 + 1)
전략 이름: "rsi-bollinger" | 시장 상태: SIDEWAYS | 보유 기간: 1~5일 (단기~스윙)

RSI (Wilder's Smoothed, 14일):
  avgGain = (avgGain × 13 + 당일 상승폭) / 14
  avgLoss = (avgLoss × 13 + 당일 하락폭) / 14
  RSI     = 100 - (100 / (1 + avgGain/avgLoss))

볼린저밴드 (20일, 2σ):
  중심선  = MA20
  upper  = MA20 + 2σ (BigDecimal.sqrt(MathContext) 사용)
  lower  = MA20 - 2σ

BUY  조건: RSI < 30 AND close < lower  (두 지표 동시 과매도)
SELL 조건: RSI > 70 OR close ≥ middle (과매수 OR 중심선 회귀)
```

### `MarketStateService.java`

```java
private final AtomicReference<MarketState> currentState = new AtomicReference<>(SIDEWAYS);
// 기본값 SIDEWAYS (보수적 판단)

refresh():
  candles = marketClient.getIndexCandles("0001", maPeriod + 5)
  ma20    = 최근 maPeriod개 종가 SMA
  lastClose = candles.get(last).getClosePrice()

  lastClose > ma20 → BULLISH
  lastClose ≤ ma20 → SIDEWAYS
  데이터 부족 시   → SIDEWAYS (현재 상태 유지)
```

### `StrategyEngine.java`

```java
// Spring이 @Component 붙은 TradingStrategy 구현체 전부를 자동 주입
private final List<TradingStrategy> strategies;
private volatile List<String> watchTickers;  // 동적 갱신 (MarketStateScheduler가 갱신)

@PostConstruct
void init() { watchTickers = strategyConfig.getWatchTickers(); }  // yml fallback 초기화

void updateWatchTickers(List<String> tickers) { watchTickers = tickers; }  // 08:30 갱신

public void run() {
    if (adminConfigStore.isPaused()) { log.info("[Engine] 매매 중단 상태 — 스킵"); return; }
    positions       = portfolioClient.getPositions()          // ① 보유 포지션 조회
    marketState     = marketStateService.getCurrentState()    // ② 시장 상태 확인
    activeNames     = getActiveStrategyNames(marketState)     // ③ 전략 필터링
    maxPositions    = adminConfigStore.getMaxPositions()      // ④ 최대 보유 수 (AdminConfigStore)
    int[] boughtThisRun = {0}                                 // ⑤ 이번 사이클 신규 매수 수

    for (ticker in watchTickers):
        currentPrice = marketClient.getCurrentPrice(ticker)
        historical   = marketClient.getCandles(ticker, candleDays)
        liveCandle   = CandleDto(openPrice, closePrice=currentPrice, highPrice, lowPrice)
        allCandles   = historical + [liveCandle]

        for (strategy in strategies):
            if (strategy.getName() not in activeNames) skip
            if (allCandles.size() < minimumCandles) skip

            signal = strategy.evaluate(ticker, allCandles)

            if (signal.isBUY()):
                ① !currentPrice.isSafe() → 시장경보 스킵
                ② positions.contains(ticker) → 이미 보유 스킵
                ③ positions.size() + boughtThisRun[0] >= maxPositions → 한도 초과 스킵
            if (handleSignal(signal, positions) && signal.isBUY()):
                boughtThisRun[0]++

        trailingStopService.check(ticker, currentPrice, positions)
        timeCutService.checkAndCut(ticker, currentPrice, positions)
}

// handleSignal(): BUY — quantity = floor(adminConfigStore.getInvestAmountKrw() / price), SELL — portfolio 전량
// getActiveStrategyNames(): BULLISH→["volatility-breakout","golden-cross"], SIDEWAYS→["rsi-bollinger"]
```

### 스케줄러 전체 구조

| Cron | 클래스 | 역할 |
|------|--------|------|
| `0 20 8 * * MON-FRI` | `StockScreenerService` (market-service) | stock-universe.json 로드 → 코스피200+코스닥150 목록 갱신 |
| `0 30 8 * * MON-FRI` | `MarketStateScheduler` | ① market-service에서 감시 종목 목록 조회 → watchTickers 갱신<br>② 코스피 MA20 → 시장 상태 판별 |
| `0 5/5 9-15 * * MON-FRI` | `StrategyScheduler` | 전략 실행 + 트레일링 스탑 + 타임 컷 |
| `0 20 15 * * MON-FRI` | `ForceExitScheduler` | 변동성 돌파 포지션 강제 청산 |

> 모든 스케줄러에 `zone = "Asia/Seoul"` 설정 — KST 기준으로 동작

```java
// StrategyScheduler: cron만으로는 15:25~15:55도 실행되므로 코드로 추가 차단
if (hour == 15 && minute > 20) return;
// 실제 실행: 09:05, 09:10 ... 15:15, 15:20 (최대 76회/일)
```

### `TrailingStopService.java`

```
peakPrices: ConcurrentHashMap<String, BigDecimal>  (메모리, 재시작 시 초기화)

check(ticker, currentPrice, positions):
  보유 포지션 없으면 스킵
  peakPrice = max(peakPrice, currentPrice)          ← 고점 갱신
  stopPrice = peakPrice × (1 - stopPercent/100)     ← 7% 기준

  currentPrice ≤ stopPrice → SELL 주문 + Slack 알림 (🛑 [트레일링 스탑])
```

### `TimeCutService.java`

```
buyDates: ConcurrentHashMap<String, LocalDate>  (메모리)

recordBuy(ticker, strategyName):
  applicable-strategies에 포함된 전략(rsi-bollinger)만 기록

checkAndCut(ticker, currentPrice, positions):
  보유 포지션 없으면 스킵
  경과 거래일 = TradingCalendar.tradingDaysBetween(buyDate, today)
  경과 거래일 ≥ maxHoldingDays → SELL 주문 + Slack 알림 (⏱️ [타임 컷])
```

### `TradingCalendar.java`

```java
tradingDaysBetween(from, to)
  // 주말(토/일) 제외 거래일 수 반환
  // 예) 월→목 = 3거래일, 금→월 = 1거래일

isTradingDay(date)
  // 토요일(6), 일요일(7) 제외
  // 공휴일은 별도 처리 안 함 (KIS API 오류로 대응)
```

### `MarketClient.java` / `OrderClient.java` / `PortfolioClient.java`

```java
// MarketClient
GET http://localhost:8081/api/stocks/{ticker}/candles?days=60  → List<CandleDto>
GET http://localhost:8081/api/stocks/{ticker}/price            → StockPriceDto (marketWarnCode 포함)
GET http://localhost:8081/api/index/{code}/candles?days=N      → List<CandleDto> (지수)
GET http://localhost:8081/internal/screened-tickers            → List<String> (감시 종목 목록)
//   실패 시 빈 List 반환 → StrategyEngine은 기존 watchTickers(yml) 유지

// OrderClient
POST http://localhost:8082/api/orders/buy  { ticker, quantity, price } → 성공 true / 실패 false
POST http://localhost:8082/api/orders/sell { ticker, quantity, price }

// PortfolioClient
GET http://localhost:8083/api/portfolio → List<PortfolioItemDto>

// @Qualifier로 세 WebClient 구분:
@Qualifier("marketWebClient")    private final WebClient marketWebClient;
@Qualifier("orderWebClient")     private final WebClient orderWebClient;
@Qualifier("portfolioWebClient") private final WebClient portfolioWebClient;
```

### `SlackNotifier.java`

```
sendSignal(signal)            → 매수/매도 신호 발생 시 🟢/🔴 알림
sendOrderFilled(signal)       → 체결 성공 ✅ / 실패 ❌ 알림
sendError(message)            → 전략 오류 ⚠️ 알림
sendServiceStarted()          → 🟢 *strategy-service* 시작 (ServiceLifecycleNotifier 위임용)
sendServiceStopped()          → 🔴 *strategy-service* 종료 (ServiceLifecycleNotifier 위임용)
(트레일링 스탑/타임 컷 알림은 각 Service에서 직접 호출)

이중 안전장치:
  ① enabled=false → 로그만 출력, 실제 발송 안 함
  ② webhookUrl="PLACEHOLDER" → 경고 로그 출력
  두 조건 모두 통과 시에만 Slack으로 실제 발송
```

### `ServiceLifecycleNotifier.java` (strategy-service)

```java
@Component @RequiredArgsConstructor
public class ServiceLifecycleNotifier implements ApplicationListener<ApplicationReadyEvent> {
    private final SlackNotifier slackNotifier;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        slackNotifier.sendServiceStarted();  // 🟢 strategy-service 시작
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        slackNotifier.sendServiceStopped();  // 🔴 strategy-service 종료
    }
}
```

나머지 4개 서비스(market/order/portfolio/api-gateway)도 동일한 `ServiceLifecycleNotifier`를 가지지만, `SlackNotifier` 없이 `WebClient`를 직접 사용합니다. api-gateway는 Lombok이 없으므로 `@Slf4j` 대신 `LoggerFactory.getLogger()`를 사용합니다.

### `SignalDto.java`

```java
enum Action { BUY, SELL, HOLD }

boolean isTradeSignal() {
    return action == BUY || action == SELL;
    // HOLD는 주문 실행 안 함
}
```

### `StrategyController.java`

```java
POST /api/strategy/run
  → strategyEngine.run() 즉시 실행
  → 장 외 시간에도 수동 테스트 가능

POST /api/strategy/test-slack
  → 삼성전자 BUY 가상 신호 생성 → Slack 연결 확인용

GET /api/strategy/market-state
  → {"state": "BULLISH"} 또는 {"state": "SIDEWAYS"}

POST /api/strategy/refresh-market-state
  → marketStateService.refresh() 즉시 실행
  → {"state": "BULLISH"} 반환
```

### API 엔드포인트

| Method | 경로 | 설명 |
|--------|------|------|
| POST | `/api/strategy/run` | 전략 즉시 실행 (수동 트리거) |
| POST | `/api/strategy/test-slack` | Slack 알림 연결 테스트 |
| GET | `/api/strategy/market-state` | 현재 시장 상태 조회 |
| POST | `/api/strategy/refresh-market-state` | 시장 상태 수동 갱신 |
| GET | `/api/strategy/admin/status` | 관리자 설정 조회 (paused, investAmountKrw, maxPositions) |
| POST | `/api/strategy/admin/pause` | 매매 긴급 정지 |
| POST | `/api/strategy/admin/resume` | 매매 재개 |
| PATCH | `/api/strategy/admin/config` | 투자 설정 변경 (부분 업데이트 지원) |

---

## 7. 서비스 간 통신 정리

### HTTP 통신

| 호출자 | 대상 | 경로 | 목적 |
|--------|------|------|------|
| 브라우저/앱 | api-gateway | `/api/**` | 단일 진입점 |
| api-gateway | market-service | `/api/stocks/**` | 라우팅 |
| api-gateway | order-service | `/api/orders/**` | 라우팅 |
| api-gateway | portfolio-service | `/api/portfolio/**` | 라우팅 |
| api-gateway | strategy-service | `/api/strategy/**` | 라우팅 |
| order-service | market-service | `/internal/token` | KIS 토큰 위임 |
| portfolio-service | market-service | `/internal/token` | KIS 토큰 위임 |
| strategy-service | market-service | `/api/stocks/{ticker}/candles` | 일봉 조회 |
| strategy-service | market-service | `/api/stocks/{ticker}/price` | 현재가 조회 (LiveCandle, 시장경보) |
| strategy-service | market-service | `/api/index/{code}/candles` | 지수 일봉 (시장 상태 판별) |
| strategy-service | market-service | `/internal/screened-tickers` | 감시 종목 목록 조회 (08:30 갱신) |
| strategy-service | order-service | `/api/orders/buy`, `/api/orders/sell` | 주문 위임 |
| strategy-service | portfolio-service | `/api/portfolio` | 보유 포지션 조회 (리스크 관리 + BUY 중복 방지) |

### Kafka 통신

| 발행자 | 토픽 | 소비자 | 목적 |
|--------|------|--------|------|
| order-service | `order-events` | portfolio-service | 주문 체결 → 포트폴리오 자동 갱신 |

### 외부 서비스 통신

| 호출자 | 대상 | 목적 |
|--------|------|------|
| strategy-service (`SlackNotifier`) | Slack Incoming Webhook | 매매 신호, 체결, 오류, 기동/종료 알림 |
| market/order/portfolio/api-gateway (`ServiceLifecycleNotifier`) | Slack Incoming Webhook | 서비스 기동/종료 알림 |
| frontend Vite dev server (`vite.config.js`) | Slack Incoming Webhook | 프론트엔드 기동/종료 알림 |

**HTTP vs Kafka 선택 기준:**
- **HTTP**: 즉각적인 응답이 필요한 경우 (조회, 주문 실행)
- **Kafka**: 응답을 기다리지 않아도 되는 비동기 이벤트 (포트폴리오 갱신)

---

## 8. 공통 패턴 정리

### KIS 모드 설정 패턴

모든 서비스에서 동일하게 사용합니다:

```yaml
kis:
  mode: paper  # mock | paper | real
```

```java
public boolean isMock()  { return "mock".equals(mode); }
public boolean isPaper() { return "paper".equals(mode); }
public boolean isReal()  { return "real".equals(mode); }

// 사용 예시
if (kisApiConfig.isMock()) return getMockData();
return getRealData();
```

### `@ConfigurationProperties` 패턴

`application.yml` 설정을 Java 객체로 자동 바인딩합니다:

```java
@ConfigurationProperties(prefix = "candle")
public class CandleConfig {
    private List<String> watchTickers;  // candle.watch-tickers
    private int defaultDays = 60;       // candle.default-days
}
// @ConfigurationPropertiesScan 으로 자동 Bean 등록
```

### DTO `from()` 정적 팩토리 메서드 패턴

Entity → DTO 변환을 DTO 내부에서 처리합니다:

```java
public class PortfolioItemDto {
    public static PortfolioItemDto from(Portfolio p) {
        return PortfolioItemDto.builder()
                .ticker(p.getTicker())
                ...
                .build();
    }
}
// 사용: portfolios.stream().map(PortfolioItemDto::from).toList()
```

### `@PrePersist` / `@PreUpdate` 패턴

DB 저장 시 자동으로 시각을 설정합니다:

```java
@PrePersist
void prePersist() {
    this.createdAt = LocalDateTime.now();  // 최초 저장 시 1회
}

@PrePersist @PreUpdate
void preUpdate() {
    this.updatedAt = LocalDateTime.now();  // 저장/수정 시마다
}
```

### Lombok 어노테이션 사용 기준

| 어노테이션 | 용도 |
|------------|------|
| `@Getter` | 모든 필드 getter 자동 생성 |
| `@Setter` | setter 필요한 클래스만 (Entity, Request DTO) |
| `@Builder` | 객체 생성 시 빌더 패턴 사용 (Response DTO, Entity 생성) |
| `@NoArgsConstructor` | JPA Entity 필수 (기본 생성자) |
| `@AllArgsConstructor` | @Builder와 함께 사용 |
| `@RequiredArgsConstructor` | final 필드 DI (Service, Controller) |
| `@Slf4j` | log.info(), log.error() 사용 |
