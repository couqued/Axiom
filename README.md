# Automated Stock Trading System

MSA based Backend + React PWA Frontend used Stock Auto Trade Project

---

## 목차

1. [프로젝트 배경](#1-프로젝트-배경)
2. [기술 스택](#2-기술-스택)
3. [전체 아키텍처](#3-전체-아키텍처)
4. [디렉토리 구조](#4-디렉토리-구조)
5. [서비스별 상세 설명](#5-서비스별-상세-설명)
6. [DB 스키마](#6-db-스키마)
7. [API 엔드포인트](#7-api-엔드포인트)
8. [Kafka 이벤트 흐름](#8-kafka-이벤트-흐름)
9. [KIS OpenAPI 연동](#9-kis-openapi-연동)
10. [환경 설정](#10-환경-설정)
11. [실행 방법](#11-실행-방법)
12. [서비스 관리 명령어](#12-서비스-관리-명령어)
13. [스마트폰 접속 방법](#13-스마트폰-접속-방법)
14. [KIS API 키 발급 후 전환 방법](#14-kis-api-키-발급-후-전환-방법)
15. [개발 로드맵](#15-개발-로드맵)
16. [변경 이력 (Changelog)](#16-변경-이력-changelog)

---

## 1. 프로젝트 배경

- **목표**: 한국투자증권(KIS) OpenAPI를 활용한 실제 국내 주식 자동 트레이딩
- **구조**: MSA (마이크로서비스 아키텍처)로 서비스 독립성 확보
- **접속**: PC 브라우저 + 스마트폰 홈화면(PWA) 모두 지원
- **현재 상태**: KIS API 키 미발급 → mock 모드로 개발/테스트 진행

---

## 2. 기술 스택

| 영역 | 기술 |
|------|------|
| Backend | Spring Boot 3.4.3, Java 21 |
| API Gateway | Spring Cloud Gateway |
| 메시지 큐 | Apache Kafka |
| Database | PostgreSQL 16 |
| ORM | Spring Data JPA / Hibernate |
| 외부 API | KIS (한국투자증권) OpenAPI |
| Frontend | React 19, Vite 7 |
| PWA | Web App Manifest |
| 인프라 | Docker, Docker Compose |
| 원격 접속 | Tailscale VPN (권장) |

---

## 3. 전체 아키텍처

```
┌─────────────────────────────────────────┐
│         스마트폰 / PC 브라우저            │
│         React PWA (port 5173)           │
└──────────────────┬──────────────────────┘
                   │ HTTP
                   ▼
┌─────────────────────────────────────────┐
│          api-gateway (port 8080)        │
│          Spring Cloud Gateway           │
└───┬──────────────┬─────────────┬──────┬─┘
    │              │             │      │
    ▼              ▼             ▼      ▼
┌────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ market │  │  order   │  │portfolio │  │strategy  │
│service │  │ service  │  │ service  │  │ service  │
│ :8081  │  │  :8082   │  │  :8083   │  │  :8084   │
│        │  │          │  │          │  │          │
│시세/종목│  │매수/매도  │  │포트폴리오│  │자동매매  │
│일봉수집 │  │주문      │  │잔고      │  │전략/Slack│
└───┬────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
    │            │             │              │
    │     ┌──────▼──────┐      │        ┌─────▼──────┐
    │     │    Kafka    │◄─────┘        │   Slack    │
    │     │order-events │  (consume)    │  Webhook   │
    │     └─────────────┘               └────────────┘
    │◄─────────────────────────────────────────────────┐
    │  (캔들 데이터 조회 / 매수·매도 주문 위임)          │
    └────────────────────────────────────────────────►─┘
                                                strategy-service

     ▼  (KIS API 키 발급 후)
┌─────────────────────────┐
│   KIS OpenAPI           │
│ openapi.koreainvestment │
│       .com:9443         │
└─────────────────────────┘

┌──────────────────────────────┐
│   PostgreSQL (Docker)        │
│   port 5432                  │
│   schemas: orders,           │
│            portfolio,        │
│            market            │
└──────────────────────────────┘
```

**서비스 간 통신:**
- `order-service → Kafka`: 주문 체결 이벤트 발행
- `portfolio-service ← Kafka`: 체결 이벤트 소비 → 포트폴리오 자동 갱신
- `strategy-service → market-service`: REST (일봉 캔들 데이터 조회, 감시 종목 목록 조회)
- `strategy-service → order-service`: REST (자동 매수/매도 주문 위임)
- `order-service, portfolio-service → market-service`: REST (KIS Access Token 위임 조회 `/internal/token`)

---

## 4. 디렉토리 구조

```
axiom/
├── README.md                          # 이 문서
├── docker-compose.yml                 # 로컬 인프라 (PostgreSQL + Kafka)
├── init-db.sql                        # DB 스키마 초기화 (orders, portfolio)
├── settings.gradle                    # Gradle 멀티 프로젝트 루트
│
├── api-gateway/                       # Spring Cloud Gateway (port 8080)
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/axiom/gateway/
│       │   └── GatewayApplication.java
│       └── resources/
│           └── application.yml        # 라우팅 규칙 + CORS
│
├── market-service/                    # 시세/종목 서비스 (port 8081)
│   ├── build.gradle
│   └── src/main/java/com/axiom/market/
│       ├── MarketApplication.java
│       ├── config/KisApiConfig.java   # KIS WebClient 설정
│       ├── controller/
│       │   ├── StockController.java       # GET /api/stocks/**
│       │   └── InternalMarketController.java  # GET /internal/screened-tickers (내부용)
│       ├── service/
│       │   ├── KisMarketApiService.java   # 현재가 조회 (mock/실제, 시장경보 코드 포함)
│       │   ├── StockSearchService.java    # 종목 검색 (mock 데이터)
│       │   └── StockScreenerService.java  # 코스피200+코스닥150 유니버스 로드 및 캐싱
│       └── dto/
│           ├── StockPriceDto.java         # marketWarnCode (시장경보) 포함
│           ├── StockInfoDto.java
│           └── StockUniverse.java         # stock-universe.json 역직렬화 DTO
│   └── src/main/resources/
│       └── stock-universe.json            # 코스피200 + 코스닥150 종목 코드 목록
│
├── order-service/                     # 주문 서비스 (port 8082)
│   ├── build.gradle
│   └── src/main/java/com/axiom/order/
│       ├── OrderApplication.java
│       ├── controller/
│       │   └── OrderController.java   # POST /api/orders/buy|sell, GET /api/orders
│       ├── service/
│       │   ├── OrderService.java      # 주문 처리 + Kafka 발행
│       │   └── KisOrderApiService.java   # KIS 주문 API (mock/실제)
│       ├── entity/TradeOrder.java     # 주문 엔티티 (JPA)
│       ├── repository/TradeOrderRepository.java
│       ├── kafka/OrderEventProducer.java  # Kafka 이벤트 발행
│       └── dto/
│           ├── OrderRequest.java
│           └── OrderResponse.java
│
├── portfolio-service/                 # 포트폴리오 서비스 (port 8083)
│   ├── build.gradle
│   └── src/main/java/com/axiom/portfolio/
│       ├── PortfolioApplication.java
│       ├── controller/
│       │   └── PortfolioController.java  # GET /api/portfolio, /api/portfolio/balance
│       ├── service/
│       │   ├── PortfolioService.java     # 포트폴리오 CRUD + 손익 계산
│       │   └── KisAccountApiService.java # KIS 잔고 조회 (mock/실제)
│       ├── entity/Portfolio.java      # 보유 주식 엔티티 (JPA)
│       ├── repository/PortfolioRepository.java
│       ├── kafka/OrderEventConsumer.java  # Kafka 이벤트 소비
│       └── dto/PortfolioItemDto.java
│
├── strategy-service/                  # 자동매매 전략 서비스 (port 8084)
│   ├── build.gradle
│   └── src/main/java/com/axiom/strategy/
│       ├── StrategyApplication.java
│       ├── config/StrategyConfig.java    # @ConfigurationProperties, WebClient 3개 Bean
│       ├── controller/
│       │   └── StrategyController.java  # POST /api/strategy/run, GET /api/strategy/market-state
│       ├── strategy/
│       │   ├── TradingStrategy.java            # 전략 인터페이스
│       │   ├── GoldenCrossStrategy.java        # MA5/MA20 골든크로스·데드크로스
│       │   ├── VolatilityBreakoutStrategy.java # 변동성 돌파 (상승장 단기)
│       │   └── RsiBollingerStrategy.java       # RSI+볼린저밴드 통합 (횡보장)
│       ├── engine/StrategyEngine.java   # 전략 순회 실행 + 시장 상태 필터링
│       ├── scheduler/
│       │   ├── StrategyScheduler.java    # 평일 09:05~15:20 5분 주기
│       │   ├── MarketStateScheduler.java # 평일 08:30 시장 상태 갱신
│       │   └── ForceExitScheduler.java   # 평일 15:20 변동성 돌파 포지션 강제 청산
│       ├── service/
│       │   ├── MarketStateService.java  # 코스피 MA로 시장 상태 판별
│       │   ├── TrailingStopService.java # 트레일링 스탑 (고점 -7%)
│       │   └── TimeCutService.java      # 타임 컷 (3거래일)
│       ├── client/
│       │   ├── MarketClient.java        # market-service 캔들/현재가/지수 조회
│       │   ├── OrderClient.java         # order-service 주문 위임
│       │   └── PortfolioClient.java     # portfolio-service 보유 포지션 조회
│       ├── notification/SlackNotifier.java   # Slack Incoming Webhook 알림
│       ├── util/TradingCalendar.java    # 거래일 계산 유틸
│       └── dto/
│           ├── CandleDto.java
│           ├── SignalDto.java           # BUY / SELL / HOLD
│           ├── StockPriceDto.java       # 현재가 (LiveCandle 생성용, marketWarnCode 포함)
│           ├── PortfolioItemDto.java    # 보유 포지션
│           └── OrderRequest.java
│
└── frontend/                          # React PWA (port 5173)
    ├── index.html                     # PWA 메타태그 포함
    ├── public/
    │   └── manifest.json              # PWA 설치 설정
    ├── package.json
    └── src/
        ├── App.jsx                    # 탭 네비게이션 (대시보드/검색/주문/내역)
        ├── App.css                    # 다크 테마, 모바일 최적화 UI
        ├── api/
        │   └── stockApi.js            # 백엔드 API 호출 헬퍼
        └── pages/
            ├── Dashboard.jsx          # 포트폴리오 현황 + 계좌 잔고
            ├── StockSearch.jsx        # 종목 검색 + 현재가
            ├── OrderForm.jsx          # 매수/매도 주문 폼
            └── TradeHistory.jsx       # 주문/체결 내역
```

---

## 5. 서비스별 상세 설명

### api-gateway (port 8080)

Spring Cloud Gateway 기반 단일 진입점.

- 프론트엔드는 모든 API를 `http://localhost:8080`으로 호출
- 경로에 따라 각 마이크로서비스로 라우팅
- 전역 CORS 처리

**라우팅 규칙:**

| 요청 경로 | 라우팅 대상 | 변환 |
|-----------|-----------|------|
| `/api/market/**` | market-service:8081 | `/api/market/stocks/...` → `/api/stocks/...` |
| `/api/orders/**` | order-service:8082 | 경로 유지 |
| `/api/portfolio/**` | portfolio-service:8083 | 경로 유지 |
| `/api/strategy/**` | strategy-service:8084 | 경로 유지 |

---

### market-service (port 8081)

종목 검색, 실시간 시세 조회, 일봉(OHLCV) 데이터 수집 담당.

- **mock 모드**: 삼성전자, SK하이닉스 등 14개 주요 종목 랜덤 시세 반환
- **실제 모드**: KIS API `inquire-price` 호출 (API 키 필요)
- **일봉 수집**: 매일 15:40 KIS `inquire-daily-itemchartprice` 호출 → `market.daily_candles` 저장
- **토큰 중앙화**: KIS Access Token을 market-service에서 단독 발급 → `GET /internal/token`으로 타 서비스에 제공

---

### order-service (port 8082)

매수/매도 주문 처리 담당. PostgreSQL `orders` 스키마에 저장.

**주문 처리 흐름:**
```
OrderController.buy/sell()
  → OrderService.placeOrder()
    → TradeOrder 생성 (status: PENDING)
    → KisOrderApiService.placeOrder() → KIS API 또는 mock
    → status: FILLED, filledAt 설정
    → DB 저장
    → OrderEventProducer.publishOrderFilled() → Kafka
```

---

### portfolio-service (port 8083)

보유 주식 현황 관리. Kafka 이벤트를 소비해 자동 갱신.

**평균 단가 계산 (매수 시):**
```
새 평균단가 = (기존 총투자금 + 신규 투자금) / 전체 수량
```

**매도 처리:**
- 부분 매도: 수량 차감, 총 투자금 차감
- 전량 매도: 해당 종목 레코드 삭제

---

### strategy-service (port 8084)

자동매매 전략 실행 및 Slack 알림 담당.

- **감시 종목 관리**: 코스피200 + 코스닥150(107개)을 `stock-universe.json`에서 로드. 매일 08:30 `market-service /internal/screened-tickers`에서 동적으로 갱신. 서비스 재시작 시 `yml watch-tickers`로 fallback.
- **하이브리드 전략 엔진**: 시장 상태(BULLISH/SIDEWAYS)를 먼저 판별한 뒤 적합한 전략 자동 선택
  - **상승장(BULLISH)**: 변동성 돌파 + 골든크로스 동시 실행
  - **횡보장(SIDEWAYS)**: RSI + 볼린저밴드 통합 전략 실행
- **시장 상태 판별**: 매일 08:30 코스피 지수 20일 이동평균 계산 → BULLISH / SIDEWAYS 분류
- **포지션 사이징**: 1회 매수 금액 50만 원 기준 수량 자동 계산(`floor(투자금액/현재가)`). 동시 최대 3종목. BUY 3단계 가드(시장경보/중복 방지/maxPositions 초과). SELL 시 portfolio 보유 수량 전량 매도.
- **리스크 관리**: 트레일링 스탑(고점 -7%) + 타임 컷(RSI+볼린저 매수 후 3거래일)
- **스케줄러**: 평일 09:05 ~ 15:20 사이 5분마다 자동 실행 / 15:20 변동성 돌파 강제 청산
- **Slack 알림**: 매매 신호 발생 시 + 주문 체결 결과 + 트레일링 스탑/타임 컷 청산 알림
- **수동 트리거**: `POST /api/strategy/run` — 즉시 실행 (테스트용)

**자동매매 흐름:**
```
StockScreenerService (매일 08:20, market-service)
  → stock-universe.json 로드 → 코스피200+코스닥150 종목 목록 캐싱

MarketStateScheduler (매일 08:30, strategy-service)
  → MarketClient → market-service /internal/screened-tickers
    → StrategyEngine.watchTickers 동적 갱신
  → MarketStateService.refresh()
    → MarketClient → market-service (코스피 지수 캔들 조회)
    → MA20 계산 → MarketState 갱신 (BULLISH / SIDEWAYS)

StrategyScheduler (5분 주기)
  → StrategyEngine.run()
    → PortfolioClient → portfolio-service (보유 포지션 조회)
    → 각 ticker별:
        MarketClient → market-service (현재가 + 캔들 조회)
        LiveCandle 생성 (현재가를 당일 캔들로 주입)
        시장 상태에 따라 전략 선택:
          BULLISH  → [변동성 돌파, 골든크로스]
          SIDEWAYS → [RSI+볼린저밴드]
        전략.evaluate() → BUY / SELL / HOLD
        BUY 3단계 가드:
          ① 시장경보 종목(isSafe() = false) → 스킵
          ② 이미 보유 중 → 스킵
          ③ 보유 수량 + 이번 사이클 매수 수 ≥ maxPositions(3) → 스킵
        수량 계산 = floor(50만 원 / 현재가)
        SlackNotifier.sendSignal() (신호 알림)
        OrderClient → order-service (BUY/SELL 시 주문)
        SlackNotifier.sendOrderFilled() (체결 결과 알림)
        TrailingStopService.check() (트레일링 스탑)
        TimeCutService.checkAndCut() (타임 컷)

ForceExitScheduler (매일 15:20)
  → 변동성 돌파 전략 당일 매수 포지션 강제 청산
```

---

### frontend (port 5173)

React 19 + Vite 7 기반 PWA.

- **다크 테마**, 모바일 앱 스타일 UI
- **하단 탭 네비게이션** (대시보드 / 종목검색 / 주문 / 매매내역)
- max-width 480px → 스마트폰 화면에 최적화
- PWA manifest → 스마트폰 홈화면에 아이콘으로 설치 가능

---

## 6. DB 스키마

PostgreSQL 단일 인스턴스, 스키마 분리 방식.

### trade_orders (orders 스키마)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | 자동 증가 |
| ticker | VARCHAR(10) | 종목코드 (예: 005930) |
| stock_name | VARCHAR(50) | 종목명 |
| order_type | VARCHAR (BUY/SELL) | 매수/매도 구분 |
| quantity | INTEGER | 주문 수량 |
| price | NUMERIC(15,2) | 주문 단가 |
| total_amount | NUMERIC(15,2) | 총 거래금액 |
| status | VARCHAR | PENDING / FILLED / CANCELLED / FAILED |
| kis_order_id | VARCHAR(50) | KIS 주문번호 |
| created_at | TIMESTAMP | 주문 시각 |
| filled_at | TIMESTAMP | 체결 시각 |

### portfolio (portfolio 스키마)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | 자동 증가 |
| ticker | VARCHAR(10) UNIQUE | 종목코드 |
| stock_name | VARCHAR(50) | 종목명 |
| quantity | INTEGER | 보유 수량 |
| avg_price | NUMERIC(15,2) | 평균 매수 단가 |
| total_invest | NUMERIC(15,2) | 총 투자금액 |
| updated_at | TIMESTAMP | 최종 갱신 시각 |

### daily_candles (market 스키마)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | 자동 증가 |
| ticker | VARCHAR(10) | 종목코드 |
| trade_date | DATE | 거래일 |
| open_price | NUMERIC(15,2) | 시가 |
| high_price | NUMERIC(15,2) | 고가 |
| low_price | NUMERIC(15,2) | 저가 |
| close_price | NUMERIC(15,2) | 종가 |
| volume | BIGINT | 거래량 |

> UNIQUE 제약: `(ticker, trade_date)` — 동일 날짜 중복 저장 방지

---

## 7. API 엔드포인트

> 모든 호출은 api-gateway (`http://localhost:8080`)를 통해 진행

### Market Service

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/market/stocks/{ticker}/price` | 현재가 조회 |
| GET | `/api/market/stocks/search?query=삼성` | 종목 검색 |
| GET | `/api/market/stocks/{ticker}` | 종목 상세 정보 |

**현재가 응답 예시:**
```json
{
  "ticker": "005930",
  "stockName": "삼성전자",
  "currentPrice": 74900,
  "changeAmount": -100,
  "changeRate": -0.13,
  "highPrice": 75400,
  "lowPrice": 74500,
  "openPrice": 75000,
  "volume": 12345678,
  "fetchedAt": "2025-01-15T10:30:00",
  "mock": true
}
```

### Order Service

| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/orders/buy` | 매수 주문 |
| POST | `/api/orders/sell` | 매도 주문 |
| GET | `/api/orders` | 전체 주문 내역 (최신순) |
| GET | `/api/orders/ticker/{ticker}` | 종목별 주문 내역 |

**주문 요청 예시:**
```json
{
  "ticker": "005930",
  "stockName": "삼성전자",
  "orderType": "BUY",
  "quantity": 10,
  "price": 75000
}
```

**주문 응답 예시:**
```json
{
  "id": 1,
  "ticker": "005930",
  "stockName": "삼성전자",
  "orderType": "BUY",
  "quantity": 10,
  "price": 75000,
  "totalAmount": 750000,
  "status": "FILLED",
  "kisOrderId": "MOCK-A1B2C3D4",
  "createdAt": "2025-01-15T10:30:00",
  "filledAt": "2025-01-15T10:30:00"
}
```

### Portfolio Service

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/portfolio` | 보유 주식 현황 |
| GET | `/api/portfolio/balance` | 계좌 잔고 |

### Strategy Service

| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/strategy/run` | 전략 즉시 실행 (수동 트리거) |
| POST | `/api/strategy/test-slack` | Slack 알림 연결 테스트 |
| GET | `/api/strategy/market-state` | 현재 시장 상태 조회 (BULLISH/SIDEWAYS) |
| POST | `/api/strategy/refresh-market-state` | 시장 상태 수동 갱신 |

> strategy-service는 스케줄러(평일 09:05~15:20, 5분 주기)로 자동 실행됩니다.
> `/api/strategy/run`은 장 외 시간에도 수동으로 테스트할 때 사용합니다.

### Market Service — 내부 호출 (strategy-service → market-service)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/stocks/{ticker}/candles?days=60` | 일봉 데이터 조회 |
| GET | `/api/stocks/{ticker}/price` | 현재가 조회 (LiveCandle 생성용) |
| GET | `/api/index/{code}/candles?days=25` | 지수 일봉 조회 (코스피: `0001`, 코스닥: `1001`) |
| GET | `/internal/screened-tickers` | 코스피200+코스닥150 감시 종목 목록 조회 |

---

## 8. Kafka 이벤트 흐름

**Topic**: `order-events`

```
order-service (Producer)
    ↓ 주문 체결 시 발행
{
  "eventType": "ORDER_FILLED",
  "orderId": 1,
  "ticker": "005930",
  "stockName": "삼성전자",
  "orderType": "BUY",
  "quantity": 10,
  "price": 75000,
  "totalAmount": 750000,
  "filledAt": "2025-01-15T10:30:00"
}
    ↓
portfolio-service (Consumer, groupId: portfolio-service)
    ↓ 이벤트 수신
    → BUY: addPosition() → 포트폴리오 추가/평균단가 갱신
    → SELL: reducePosition() → 수량 차감 or 종목 삭제
```

---

## 9. KIS OpenAPI 연동

### 현재 상태 (mock 모드)

`application.yml`에서 `kis.mock-mode: true`로 설정 시 모든 서비스가 가상 데이터 반환.
KIS API 키 없이도 전체 기능 테스트 가능.

### 실제 연동 시 사용하는 KIS API

| 서비스 | 기능 | TR ID (모의/실제) | URI |
|--------|------|-------|-----|
| 공통 | Access Token 발급 | - | POST `/oauth2/tokenP` |
| market-service | 국내 현재가 조회 | FHKST01010100 | GET `/uapi/domestic-stock/v1/quotations/inquire-price` |
| market-service | 일봉(OHLCV) 수집 | FHKST03010100 | GET `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` |
| order-service | 주식 매수 주문 | VTTC0802U / TTTC0802U | POST `/uapi/domestic-stock/v1/trading/order-cash` |
| order-service | 주식 매도 주문 | VTTC0801U / TTTC0801U | POST `/uapi/domestic-stock/v1/trading/order-cash` |
| portfolio-service | 잔고 조회 | VTTC8434R / TTTC8434R | GET `/uapi/domestic-stock/v1/trading/inquire-balance` |

---

## 10. 환경 설정

### 사전 요구사항

| 항목 | 버전 | 확인 명령 |
|------|------|----------|
| Java | 21 이상 | `java -version` |
| Docker Desktop | 최신 | `docker --version` |
| Node.js | 18 이상 | `node --version` |
| npm | 9 이상 | `npm --version` |

### 설정 파일 위치

| 서비스 | 설정 파일 경로 |
|--------|-------------|
| api-gateway | `api-gateway/src/main/resources/application.yml` |
| market-service | `market-service/src/main/resources/application.yml` |
| order-service | `order-service/src/main/resources/application.yml` |
| portfolio-service | `portfolio-service/src/main/resources/application.yml` |
| strategy-service | `strategy-service/src/main/resources/application.yml` |
| strategy-service (Slack) | `strategy-service/src/main/resources/application-secret.yml` |
| 인프라 | `docker-compose.yml` |

### DB 접속 정보

| 항목 | 값 |
|------|-----|
| Host | localhost:5432 |
| Database | axiom |
| Username | axiom |
| Password | axiom1234 |
| order-service 스키마 | orders |
| portfolio-service 스키마 | portfolio |

---

## 11. 실행 방법

### Step 1: Docker 인프라 실행 (PostgreSQL + Kafka)

```bash
# 프로젝트 루트에서 실행
cd D:/project/axiom
docker-compose up -d
```

정상 실행 확인:
```bash
docker ps
# axiom-postgres, axiom-kafka, axiom-zookeeper 3개 컨테이너 실행 중이어야 함
```

### Step 2: Gradle Wrapper 생성 (최초 1회)

각 서비스 폴더에서 실행:
```bash
cd api-gateway       && gradle wrapper && cd ..
cd market-service    && gradle wrapper && cd ..
cd order-service     && gradle wrapper && cd ..
cd portfolio-service && gradle wrapper && cd ..
cd strategy-service  && gradle wrapper && cd ..
```

> Gradle이 없는 경우: https://gradle.org/install/ 에서 설치

### Step 3: 백엔드 서비스 실행 (터미널 4개)

**터미널 1 - api-gateway:**
```bash
cd D:/project/axiom/api-gateway
./gradlew bootRun
# 포트 8080 기동 확인
```

**터미널 2 - market-service:**
```bash
cd D:/project/axiom/market-service
./gradlew bootRun
# 포트 8081 기동 확인
```

**터미널 3 - order-service:**
```bash
cd D:/project/axiom/order-service
./gradlew bootRun
# 포트 8082 기동 확인
```

**터미널 4 - portfolio-service:**
```bash
cd D:/project/axiom/portfolio-service
./gradlew bootRun
# 포트 8083 기동 확인
```

**터미널 5 - strategy-service:**
```bash
cd D:/project/axiom/strategy-service
./gradlew bootRun
# 포트 8084 기동 확인
# 평일 09:05~15:20 사이 5분마다 자동 전략 실행
```

### Step 4: 프론트엔드 실행

```bash
# 초기
cd D:/project/axiom/frontend
npm install
npm run dev
# http://localhost:5173 에서 확인

# 이거로 실행
cd D:/project/axiom/frontend && npm run dev
# http://localhost:5173 에서 확인
```

### Step 5: API 동작 테스트 (curl)

```bash
# 현재가 조회
curl http://localhost:8080/api/market/stocks/005930/price

# 종목 검색
curl http://localhost:8080/api/market/stocks/search?query=삼성

# 매수 주문
curl -X POST http://localhost:8080/api/orders/buy \
  -H "Content-Type: application/json" \
  -d '{"ticker":"005930","stockName":"삼성전자","orderType":"BUY","quantity":10,"price":75000}'

# 포트폴리오 조회
curl http://localhost:8080/api/portfolio

# 잔고 조회
curl http://localhost:8080/api/portfolio/balance

# 주문 내역 조회
curl http://localhost:8080/api/orders

# 자동매매 전략 수동 실행 (전략 즉시 실행)
curl -X POST http://localhost:8080/api/strategy/run

# Slack 알림 연결 테스트
curl -X POST http://localhost:8080/api/strategy/test-slack
```

## 12. 서비스 관리 명령어

> 아래 명령어는 모두 **PowerShell** 또는 **CMD**에서 실행합니다.

### 실행 상태 확인

**포트 확인 (가장 빠름)**
```powershell
netstat -ano | findstr ":808" | findstr "LISTENING"
```

| 포트 | 서비스 |
|------|--------|
| `:8080` | api-gateway |
| `:8081` | market-service |
| `:8082` | order-service |
| `:8083` | portfolio-service |
| `:8084` | strategy-service |

5개 포트가 모두 `LISTENING` 상태이면 전체 정상 실행 중.

**Health API 확인**
```powershell
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
curl http://localhost:8084/actuator/health
```
각각 `{"status":"UP"}` 응답이 오면 정상.

---

### Docker 인프라 상태 확인

```powershell
docker ps
```
`axiom-postgres`, `axiom-kafka`, `axiom-zookeeper` 컨테이너가 `Up` 상태여야 Spring Boot 서비스가 정상 동작합니다.

---

### 서비스 종료

**특정 서비스 종료** — 포트 확인 후 PID로 종료
```powershell
# 1단계: PID 확인
netstat -ano | findstr ":808" | findstr "LISTENING"

# 2단계: 해당 PID 종료 (예시)
taskkill /PID 12345 /F

# 여러 개 한번에 종료
taskkill /PID 11111 /PID 22222 /PID 33333 /PID 44444 /F
```

**Java 프로세스 전체 종료** (모든 서비스 한번에)
```powershell
taskkill /IM java.exe /F
```
> PC에서 실행 중인 **모든** Java 프로세스가 종료됩니다.

**Docker 인프라 종료**
```powershell
cd D:\kc\project\axiom
docker-compose down
```

---


## 13. 스마트폰 접속 방법

### 방법 1: 같은 WiFi (가장 간단)

1. PC의 로컬 IP 확인: `ipconfig` → IPv4 주소 (예: `192.168.1.100`)
2. 스마트폰에서 `http://192.168.1.100:5173` 접속

### 방법 2: Tailscale VPN (외부 접속 가능, 권장)

1. **PC에 Tailscale 설치**: https://tailscale.com/download
2. **스마트폰에 Tailscale 앱 설치** (Android/iOS)
3. 동일 Tailscale 계정으로 로그인
4. PC의 Tailscale IP 확인 (예: `100.64.x.x`)
5. 스마트폰에서 `http://100.64.x.x:5173` 접속
   - 외부 네트워크(LTE, 다른 WiFi)에서도 접속 가능

### 스마트폰 홈화면에 앱으로 설치 (PWA)

1. 스마트폰 Chrome에서 접속
2. 우측 상단 메뉴 → "홈 화면에 추가"
3. "Axiom 주식매매" 아이콘이 홈화면에 생성
4. 이후 앱처럼 실행 (URL 입력 불필요)

---

## 14. KIS API 키 발급 후 전환 방법

### 1. KIS OpenAPI 신청

1. 한국투자증권 계좌 개설 (없는 경우)
2. [KIS Developers](https://apiportal.koreainvestment.com) 접속
3. 앱 등록 → App Key, App Secret 발급
4. 계좌번호 확인 (XXXXXXXX-XX 형식)

### 2. 설정 파일 수정

아래 3개 파일을 동일하게 수정:

- `market-service/src/main/resources/application.yml`
- `order-service/src/main/resources/application.yml`
- `portfolio-service/src/main/resources/application.yml`

```yaml
kis:
  api:
    base-url: https://openapi.koreainvestment.com:9443
    app-key: 발급받은_APP_KEY         # ← 실제 키 입력
    app-secret: 발급받은_APP_SECRET   # ← 실제 시크릿 입력
    account-no: 계좌번호              # ← 예: 12345678-01
  mock-mode: false                    # ← true → false 변경
```

> 모의투자 환경: `base-url`을 `https://openapivts.koreainvestment.com:29443`으로 변경

### 3. 서비스 재시작

설정 변경 후 각 서비스를 재시작하면 실제 KIS API와 연동됩니다.

---

## 15. 개발 로드맵

### 완료
- [x] MSA 프로젝트 구조 구성 (4개 Spring Boot 서비스)
- [x] api-gateway 라우팅 설정
- [x] market-service (시세 조회, 종목 검색) - mock 모드
- [x] order-service (매수/매도 주문, DB 저장) - mock 모드
- [x] portfolio-service (포트폴리오 관리, 잔고 조회) - mock 모드
- [x] Kafka 이벤트 기반 포트폴리오 자동 갱신
- [x] React PWA 프론트엔드 (대시보드/종목검색/주문/매매내역)
- [x] 다크 테마 + 모바일 최적화 UI
- [x] KIS 모의투자(Paper Trading) API 연동
- [x] KIS Access Token 24시간 캐싱 + market-service 중앙화
- [x] 주식 시장 운영시간 체크 (MarketHoursChecker)
- [x] 일봉(OHLCV) 데이터 수집 및 DB 저장 (market.daily_candles)
- [x] strategy-service 구축 (자동매매 전략 엔진)
- [x] 골든크로스 전략 구현 (MA5/MA20)
- [x] 스케줄러 기반 자동 전략 실행 (평일 09:05~15:20, 5분 주기)
- [x] Slack Incoming Webhook 알림 (신호 발생 + 주문 체결)
- [x] 하이브리드 전략 아키텍처 (시장 상태 기반 전략 자동 선택)
- [x] 시장 상태 판별 (코스피 MA20 → BULLISH/SIDEWAYS)
- [x] 변동성 돌파 전략 구현 (상승장 단기 매매)
- [x] RSI + 볼린저밴드 통합 전략 구현 (횡보장 스윙 매매)
- [x] 트레일링 스탑 (고점 -7% 하락 시 자동 청산)
- [x] 타임 컷 (RSI+볼린저 매수 후 3거래일 미반등 시 강제 청산)
- [x] portfolio-service 연동 (보유 포지션 기반 리스크 관리)
- [x] 종목 스크리닝 서비스 (코스피200+코스닥150 유니버스 로드, `stock-universe.json`)
- [x] 동적 감시 종목 갱신 (매일 08:30 market-service에서 자동 갱신, yml fallback)
- [x] 시장경보 종목 BUY 스킵 (KIS `mrkt_warn_cls_code` 확인, `isSafe()`)
- [x] 포지션 사이징 (1회 50만 원, 수량 = `floor(투자금액/현재가)`)
- [x] 최대 보유 종목 수 제한 (`maxPositions=3`, BUY 3단계 가드 + `boughtThisRun` 카운터)
- [x] 전량 매도 연동 (portfolio-service 보유 수량 기반 SELL)

### 진행 예정
- [ ] KIS API 실제 연동 (실계좌 API 키 발급 후)
- [ ] 추가 전략 구현 (MACD — 추세 지속성 확인)
- [ ] 전략 설정 UI (프론트엔드에서 전략 ON/OFF, 종목·수량 설정)
- [ ] 실시간 시세 (WebSocket / SSE)
- [ ] 손익 계산 (현재가 기반 평가손익 표시)
- [ ] 알림 기능 (목표가 도달 시 푸시 알림)
- [ ] 종목 즐겨찾기

---

## 참고

- [KIS Developers 공식 문서](https://apiportal.koreainvestment.com)
- [Spring Cloud Gateway 문서](https://docs.spring.io/spring-cloud-gateway/docs/current/reference/html/)
- [Tailscale 공식 사이트](https://tailscale.com)

---

## 16. 변경 이력 (Changelog)

> 최신 버전이 맨 위에 표시됩니다. 제목 왼쪽 ▶ 를 클릭하면 상세 내용이 펼쳐집니다.

---
<details>
<summary><strong>[v0.3.0] - 2026-03-05</strong> &nbsp;·&nbsp; 종목 스크리닝 + 포지션 사이징 + 예산 관리</summary>

<br>

#### Added
- **종목 스크리닝 서비스** (`StockScreenerService`) — market-service 신규
  - `stock-universe.json`: 코스피200(85개) + 코스닥150(22개) = 107개 종목 코드 관리 (KRX 리밸런싱 주기 반영 필요)
  - `@PostConstruct` 초기 로드 + `@Scheduled(cron: 0 20 8 * * MON-FRI)` 매일 08:20 갱신
  - `GET /internal/screened-tickers`: strategy-service 전용 내부 API
  - `StockUniverse.java`: stock-universe.json 역직렬화 DTO
  - `InternalMarketController.java`: 내부 전용 엔드포인트 컨트롤러
- **동적 감시 종목 갱신** (`StrategyEngine`) — strategy-service
  - `volatile List<String> watchTickers`: 서비스 재시작 시 `yml watch-tickers`로 fallback 초기화 (`@PostConstruct`)
  - `MarketStateScheduler(08:30)`: 시장 상태 갱신 전 market-service `/internal/screened-tickers`에서 최신 종목 목록 갱신
  - `MarketClient.getScreenedTickers()` 메서드 추가
- **시장경보 종목 BUY 스킵**
  - `KisMarketApiService`: KIS API 응답의 `mrkt_warn_cls_code` 파싱 (`00`=정상, `01`=투자주의, `02`=투자경고, `03`=투자위험)
  - `StockPriceDto.isSafe()`: BUY 신호 발생 시 시장경보 종목 자동 제외
- **포지션 사이징** (`StrategyConfig.PositionSizingConfig`) — strategy-service
  - `investAmountKrw: 500000` — 1회 매수 금액 50만 원 고정
  - 매수 수량 = `floor(투자금액 / 현재가)` (최소 1주 미달 시 BUY 스킵 + Slack 경고)
- **BUY 3단계 가드** (`StrategyEngine`)
  - ① 시장경보 종목 스킵 (`isSafe()`)
  - ② 이미 보유 중인 종목 스킵 (portfolio 보유 목록과 대조)
  - ③ 최대 보유 종목 수(`maxPositions=3`) 초과 시 스킵 (`boughtThisRun[]` 카운터로 동일 사이클 내 신규 매수 수 추적)
- **SELL 전량 매도** (`StrategyEngine`)
  - 매도 신호 시 portfolio-service에서 실제 보유 수량 조회 → 전량 매도
  - 보유 없는 종목에 SELL 신호 발생 시 자동 스킵

#### Changed
- `StrategyConfig`: `orderQuantity` 제거 → `PositionSizingConfig` (`investAmountKrw`, `maxPositions`) 추가
- `application.yml` (strategy-service): `order-quantity` 제거 → `position-sizing` 블록 추가
- `MarketStateScheduler`: 시장 상태 갱신 전 감시 종목 목록 자동 갱신 역할 추가
- `StockPriceDto` (market-service, strategy-service): `marketWarnCode` 필드 + `isSafe()` 추가

#### Notes
- `stock-universe.json`은 KRX 6/12월 리밸런싱에 맞춰 수동 업데이트 필요
- 예산 구조: 50만 원 × 최대 3종목 = **최대 150만 원 동시 투자**
- `boughtThisRun[]` 카운터로 동일 5분 사이클 내 신규 매수 수를 추적하여 `maxPositions` 초과 방지
- 트레일링 스탑·타임 컷의 상태는 메모리(`ConcurrentHashMap`)에만 보관 → 서비스 재시작 시 초기화

</details>

<details>
<summary><strong>[v0.2.0] - 2026-03-03</strong> &nbsp;·&nbsp; 하이브리드 자동매매 전략 (시장 상태 기반 전략 선택 + 리스크 관리)</summary>

<br>

#### Added
- **하이브리드 전략 아키텍처** — strategy-service
  - `MarketState` enum (`BULLISH` / `SIDEWAYS`) + `MarketStateService`: 코스피 지수 20일 이동평균으로 시장 상태 판별
  - `MarketStateScheduler`: 매일 08:30 자동 갱신 (`cron: 0 30 8 * * MON-FRI`)
  - `GET /api/strategy/market-state`: 현재 시장 상태 조회
  - `POST /api/strategy/refresh-market-state`: 수동 갱신 트리거
- **변동성 돌파 전략** (`VolatilityBreakoutStrategy`) — 상승장 단기 매매
  - 목표가 = 당일 시가 + (전일 고가 - 전일 저가) × K(0.5)
  - 현재가 ≥ 목표가 → BUY (당일 중복 매수 방지 포함)
  - `ForceExitScheduler`: 매일 15:20 변동성 돌파 포지션 강제 청산 (오버나이트 방지)
- **RSI + 볼린저밴드 통합 전략** (`RsiBollingerStrategy`) — 횡보장 스윙 매매
  - BUY 조건: RSI(14) < 30 **AND** 종가 < 볼린저밴드 하단 (두 지표 동시 과매도 확인)
  - SELL 조건: RSI(14) > 70 **OR** 종가 ≥ 볼린저밴드 중심선(MA20)
  - Wilder's Smoothed RSI + 볼린저밴드 20일/2σ
- **트레일링 스탑** (`TrailingStopService`)
  - 보유 종목별 고점 추적, 고점 대비 7% 하락 시 자동 SELL
  - Slack 알림: 🛑 [트레일링 스탑] 청산 시 발송
- **타임 컷** (`TimeCutService`)
  - RSI+볼린저밴드 전략으로 매수 후 3거래일 내 미반등 시 강제 SELL
  - `TradingCalendar` 유틸: 주말 제외 거래일 계산
  - Slack 알림: ⏱️ [타임 컷] 청산 시 발송
- **LiveCandle 주입** — `StrategyEngine` 개선
  - 현재가(`getCurrentPrice()`)를 당일 캔들로 변환해 역사적 캔들 목록 마지막에 추가
  - 변동성 돌파 전략의 장중 현재가 기반 매수 조건 판별 가능
- **portfolio-service 연동** (`PortfolioClient`)
  - 보유 포지션 조회 → 트레일링 스탑·타임 컷 대상 종목 확인
- **IndexCandleService** — market-service 신규
  - 코스피(0001)/코스닥(1001) 지수 일봉 제공
  - `GET /api/index/{code}/candles?days=N` 엔드포인트 추가
  - KIS paper 모드 미지원 시 mock 데이터 자동 폴백

#### Changed
- `StrategyEngine`: 시장 상태 기반 전략 필터링 추가 (BULLISH → 변동성돌파+골든크로스, SIDEWAYS → RSI+볼린저밴드)
- `StrategyConfig`: `MarketFilterConfig`, `TrailingStopConfig`, `TimeCutConfig` 중첩 설정 클래스 추가 + portfolio WebClient Bean 추가
- `MarketClient`: `getCurrentPrice()`, `getIndexCandles()` 메서드 추가
- `CandleDto`: `@Builder`, `@AllArgsConstructor` 추가 (LiveCandle 생성용)
- `application.yml`: `market-filter`, `trailing-stop`, `time-cut`, `portfolio-service` 설정 추가

#### Notes
- 시장 상태 필터는 `strategy.market-filter.enabled: false`로 비활성화 시 모든 전략 항상 실행
- 트레일링 스탑·타임 컷의 상태(고점, 매수일)는 메모리(`ConcurrentHashMap`)에만 보관 → 서비스 재시작 시 초기화
- 변동성 돌파 전략의 강제 청산은 portfolio-service와의 연동으로 실제 보유 여부 확인 후 실행

</details>

<details>
<summary><strong>[v0.1.2] - 2026-03-02</strong> &nbsp;·&nbsp; 일봉 데이터 수집 + 자동매매 전략 엔진 (strategy-service) + Slack 알림</summary>

<br>

#### Added
- **일봉(OHLCV) 데이터 수집** — market-service
  - KIS `inquire-daily-itemchartprice` API (TR: `FHKST03010100`)로 종목별 일봉 수집
  - 매일 15:40 자동 수집 스케줄러 (`CandleCollectScheduler`)
  - PostgreSQL `market.daily_candles` 테이블 저장, `(ticker, trade_date)` UNIQUE 제약
  - mock 모드 지원: 실제 API 없이도 랜덤 캔들 데이터 생성
  - `GET /api/stocks/{ticker}/candles?days=60` 엔드포인트 추가
- **strategy-service 신규 구축** (포트 8084)
  - `TradingStrategy` 인터페이스 기반 전략 엔진 — Spring이 구현체를 자동 등록
  - **골든크로스 전략** (`GoldenCrossStrategy`): MA5/MA20 이동평균 비교
    - 전일 MA5 ≤ MA20, 당일 MA5 > MA20 → BUY 신호
    - 전일 MA5 ≥ MA20, 당일 MA5 < MA20 → SELL 신호
  - **StrategyScheduler**: 평일 09:05 ~ 15:20 사이 5분마다 자동 실행, 15:20 이후 스킵
  - **StrategyEngine**: 설정된 ticker 목록 × 활성 전략을 순회하며 평가 → 신호 발생 시 자동 주문
  - `POST /api/strategy/run` — 수동 즉시 실행 (테스트용)
  - `POST /api/strategy/test-slack` — Slack 연동 확인용 테스트 메시지 발송
- **Slack Incoming Webhook 알림** (`SlackNotifier`)
  - 매매 신호 발생 시 BUY/SELL/HOLD 블록 알림
  - 주문 체결 성공/실패 결과 알림
  - 전략 실행 오류 발생 시 에러 알림
- **api-gateway** strategy-service 라우팅 추가 (`/api/strategy/**` → 8084)
- **KIS Access Token 중앙화** — market-service에서 단독 발급, order/portfolio-service는 `GET /internal/token`으로 위임 조회 (다중 서비스 동시 토큰 요청으로 인한 403 방지)

#### Notes
- strategy-service는 market-service의 캔들 데이터와 order-service의 주문 API를 의존함 — 실행 순서: market-service → order-service → strategy-service
- 자동매매 전략은 `strategy-service/src/main/resources/application.yml`의 `strategy.watch-tickers`, `strategy.order-quantity`로 종목·수량 설정

</details>

<details>
<summary><strong>[v0.1.1] - 2026-03-01</strong> &nbsp;·&nbsp; KIS 모의투자 API 연동 + 주식 시장 운영시간 체크</summary>

<br>

#### Added
- KIS 한국투자증권 OpenAPI 모의투자(Paper Trading) 연동
  - `kis.mode: mock | paper | real` 3단계 모드 설정 (`application.yml`)
  - 실제 자격증명은 `application-secret.yml`에 분리 관리 (gitignore 적용)
  - Access Token 24시간 캐싱 (`KisTokenService`) — market-service에서 중앙 발급, 나머지 서비스는 위임 조회 (`GET /internal/token`)
  - market-service: 실시간 현재가 조회 (`GET /uapi/domestic-stock/v1/quotations/inquire-price`)
  - order-service: 모의투자 매수/매도 주문 (`POST /uapi/domestic-stock/v1/trading/order-cash`, TR ID `VTTC0802U` / `VTTC0801U`)
  - portfolio-service: 모의투자 잔고 조회 (`GET /uapi/domestic-stock/v1/trading/inquire-balance`, TR ID `VTTC8434R`)
- 주식 시장 운영시간 체크 (`MarketHoursChecker`)
  - 매수/매도 주문 시 KST 평일 09:00~15:30 외 즉시 차단 (KIS API 호출 없음)
  - `mock` 모드일 때는 24시간 주문 가능 (개발/테스트 목적)
  - 응답에 다음 개장 시각 포함 (`marketOpenAt`)

#### Fixed
- `OrderController` `/buy`, `/sell` 엔드포인트에서 `orderType` 자동 설정 누락 수정
- `TradeOrder.stockName` NOT NULL 제약 제거 (ticker로 종목 식별 가능)
- KIS 주문 응답에서 `rt_cd` 체크 추가 — 오류 시 명확한 메시지 반환

#### Notes
- 현재 `kis.mode: paper` (모의투자 연동 중)
- 공휴일 체크는 미포함 — 공휴일에는 KIS가 `MARKET_CLOSED` 오류를 반환하므로 실사용상 문제 없음

</details>

<details>
<summary><strong>[v0.1.0] - 2026-03-01</strong> &nbsp;·&nbsp; MSA 기반 주식매매 프로그램 초기 구성</summary>

<br>

#### Added
- MSA 4개 서비스 초기 구성 (api-gateway :8080, market-service :8081, order-service :8082, portfolio-service :8083)
- Docker Compose 인프라 구성 (PostgreSQL 16 + Kafka 7.5 + Zookeeper)

#### Notes
- 현재 전 서비스 `kis.mock-mode: true` 상태 (KIS API 키 미발급)
- 실제 연동 시 각 서비스 `application.yml`에서 `mock-mode: false` 및 키 입력 필요

</details>
