# 주식 자동 매매 프로젝트

주식 자동 매매를 위한 MSA 기반 백엔드 + React PWA 프론트엔드 프로젝트.

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
12. [스마트폰 접속 방법](#12-스마트폰-접속-방법)
13. [KIS API 키 발급 후 전환 방법](#13-kis-api-키-발급-후-전환-방법)
14. [개발 로드맵](#14-개발-로드맵)
15. [변경 이력 (Changelog)](#15-변경-이력-changelog)

---

## 1. 프로젝트 배경

기존 Todo 앱(Spring Boot 모놀리식 + React)을 전면 재개발.

- **목표**: 한국투자증권(KIS) OpenAPI를 활용한 실제 국내 주식 매수/매도 실행
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
└──────┬──────────────┬────────────────┬──┘
       │              │                │
       ▼              ▼                ▼
┌──────────┐  ┌──────────────┐  ┌─────────────────┐
│  market  │  │    order     │  │    portfolio    │
│ service  │  │   service    │  │    service      │
│ :8081    │  │   :8082      │  │    :8083        │
│          │  │              │  │                 │
│시세/종목  │  │매수/매도 주문 │  │포트폴리오/잔고  │
└────┬─────┘  └──────┬───────┘  └────────┬────────┘
     │               │                   │
     │        ┌──────▼──────┐            │
     │        │    Kafka    │◄───────────┘
     │        │order-events │  (consume)
     │        └─────────────┘
     │
     ▼  (KIS API 키 발급 후)
┌─────────────────────────┐
│   KIS OpenAPI           │
│ openapi.koreainvestment │
│       .com:9443         │
└─────────────────────────┘

┌─────────────────────────┐
│   PostgreSQL (Docker)   │
│   port 5432             │
│   schemas: orders,      │
│            portfolio    │
└─────────────────────────┘
```

**서비스 간 통신:**
- `order-service → market-service`: REST (주문 시 현재가 검증, 향후 추가)
- `order-service → Kafka`: 주문 체결 이벤트 발행
- `portfolio-service ← Kafka`: 체결 이벤트 소비 → 포트폴리오 자동 갱신

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
│       │   └── StockController.java   # GET /api/stocks/**
│       ├── service/
│       │   ├── KisMarketApiService.java  # 현재가 조회 (mock/실제)
│       │   └── StockSearchService.java   # 종목 검색 (mock 데이터)
│       └── dto/
│           ├── StockPriceDto.java
│           └── StockInfoDto.java
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

---

### market-service (port 8081)

종목 검색과 실시간 시세 조회 담당.

- **mock 모드**: 삼성전자, SK하이닉스 등 14개 주요 종목 랜덤 시세 반환
- **실제 모드**: KIS API `inquire-price` 호출 (API 키 필요)

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

| 서비스 | 기능 | TR ID | URI |
|--------|------|-------|-----|
| 공통 | Access Token 발급 | - | POST `/oauth2/tokenP` |
| market-service | 국내 현재가 조회 | FHKST01010100 | GET `/uapi/domestic-stock/v1/quotations/inquire-price` |
| order-service | 주식 매수 주문 | TTTC0802U | POST `/uapi/domestic-stock/v1/trading/order-cash` |
| order-service | 주식 매도 주문 | TTTC0801U | POST `/uapi/domestic-stock/v1/trading/order-cash` |
| portfolio-service | 잔고 조회 | TTTC8434R | GET `/uapi/domestic-stock/v1/trading/inquire-balance` |

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
cd api-gateway      && gradle wrapper && cd ..
cd market-service   && gradle wrapper && cd ..
cd order-service    && gradle wrapper && cd ..
cd portfolio-service && gradle wrapper && cd ..
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

### Step 4: 프론트엔드 실행

```bash
cd D:/project/axiom/frontend
npm install
npm run dev
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
```

---

## 12. 스마트폰 접속 방법

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

## 13. KIS API 키 발급 후 전환 방법

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

## 14. 개발 로드맵

### 완료
- [x] MSA 프로젝트 구조 구성 (4개 Spring Boot 서비스)
- [x] api-gateway 라우팅 설정
- [x] market-service (시세 조회, 종목 검색) - mock 모드
- [x] order-service (매수/매도 주문, DB 저장) - mock 모드
- [x] portfolio-service (포트폴리오 관리, 잔고 조회) - mock 모드
- [x] Kafka 이벤트 기반 포트폴리오 자동 갱신
- [x] React PWA 프론트엔드 (대시보드/종목검색/주문/매매내역)
- [x] 다크 테마 + 모바일 최적화 UI

### 진행 예정
- [ ] KIS API 실제 연동 (API 키 발급 후)
- [ ] KIS Access Token 자동 갱신 (2시간 만료 처리)
- [ ] 자동매매 전략 레이어 구현
  - [ ] 조건 기반 매매 로직 (이동평균, RSI 등)
  - [ ] 스케줄러 기반 자동 주문 실행
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

## 15. 변경 이력 (Changelog)

> 최신 버전이 맨 위에 표시됩니다. 제목 왼쪽 ▶ 를 클릭하면 상세 내용이 펼쳐집니다.

---

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
