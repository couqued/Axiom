package com.axiom.strategy.client;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.InvestorFlowDto;
import com.axiom.strategy.dto.TradePlanDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ml-service(Python FastAPI) REST 클라이언트.
 *
 * <p>ML 서비스 다운 시 HOLD로 폴백하여 기존 전략 흐름을 방해하지 않는다.
 */
@Slf4j
@Component
public class MlClient {

    private final WebClient mlWebClient;

    public MlClient(@Value("${ml-service.url:http://localhost:8085}") String mlServiceUrl) {
        this.mlWebClient = WebClient.builder().baseUrl(mlServiceUrl).build();
    }

    /**
     * 단일 종목 추론. ml-service 실패 시 null → 호출자가 HOLD로 간주.
     */
    public TradePlanDto predict(String ticker, List<CandleDto> candles, List<CandleDto> indexCandles,
                                double marketBreadth,
                                List<InvestorFlowDto> investorFlows,
                                InvestorFlowDto todayInvestorFlow) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("ticker", ticker);
            body.put("candles", candles);
            body.put("indexCandles", indexCandles != null ? indexCandles : Collections.emptyList());
            body.put("marketBreadth", marketBreadth);
            body.put("investorFlows", investorFlows != null ? investorFlows : Collections.emptyList());
            body.put("todayInvestorFlow", todayInvestorFlow);
            return mlWebClient.post()
                    .uri("/predict")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(MlPredictResponse.class)
                    .map(MlPredictResponse::toDto)
                    .block();
        } catch (Exception e) {
            log.warn("[MlClient] predict 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            return null;
        }
    }

    /**
     * 다수 종목 일괄 추론 (5분 사이클 최적화).
     * 실패 시 빈 map → 호출자가 개별 종목을 HOLD로 처리.
     */
    public Map<String, TradePlanDto> predictBatch(Map<String, List<CandleDto>> tickerCandles,
                                                   List<CandleDto> indexCandles) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("candles", tickerCandles);
            body.put("indexCandles", indexCandles != null ? indexCandles : Collections.emptyList());
            Map<String, MlPredictResponse> raw = mlWebClient.post()
                    .uri("/predict/batch")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, MlPredictResponse>>() {})
                    .block();
            if (raw == null || raw.isEmpty()) return Collections.emptyMap();
            Map<String, TradePlanDto> result = new ConcurrentHashMap<>();
            raw.forEach((ticker, resp) -> {
                if (resp != null) result.put(ticker, resp.toDto());
            });
            return result;
        } catch (Exception e) {
            log.warn("[MlClient] predictBatch 실패 - tickers: {}, error: {}",
                    tickerCandles.size(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 재학습 트리거 (비동기 — ml-service 측에서 백그라운드 처리).
     */
    public void triggerRetrain() {
        startTrainAsync()
                .doOnSuccess(v -> log.info("[MlClient] /train 완료"))
                .doOnError(e -> log.warn("[MlClient] /train 실패: {}", e.getMessage()))
                .subscribe();
        log.info("[MlClient] /train 트리거 — 백그라운드 학습 시작");
    }

    /** 재학습 Mono — 완료 후 콜백 체이닝용 */
    public reactor.core.publisher.Mono<Void> startTrainAsync() {
        return mlWebClient.post()
                .uri("/train")
                .retrieve()
                .bodyToMono(Void.class);
    }

    /**
     * 현재 모델 상태 조회. ml-service 미동작 시 null 반환.
     */
    public ModelStatusDto getModelStatus() {
        try {
            ModelStatusResponse resp = mlWebClient.get()
                    .uri("/model/status")
                    .retrieve()
                    .bodyToMono(ModelStatusResponse.class)
                    .block();
            if (resp == null || resp.meta == null) return null;
            ModelStatusResponse.Meta m = resp.meta;
            Map<String, String> freshness = new HashMap<>();
            if (resp.global_data_freshness != null) {
                resp.global_data_freshness.forEach((k, v) -> {
                    if (v != null && !k.equals("last_fetch_ts")) freshness.put(k, v.toString());
                });
            }
            return new ModelStatusDto(m.trained_at, m.samples, m.val_auc, m.val_mae_ret, m.val_mae_days, freshness);
        } catch (Exception e) {
            log.warn("[MlClient] getModelStatus 실패: {}", e.getMessage());
            return null;
        }
    }

    public record ModelStatusDto(
            String              trainedAt,
            Integer             samples,
            Double              valAuc,
            Double              valMaeRet,
            Double              valMaeDays,
            Map<String, String> globalDataFreshness
    ) {}

    // ── 응답 바디 모델 ─────────────────────────────────────────────────────────

    static class ModelStatusResponse {
        public boolean           ready;
        public Meta              meta;
        public Map<String, Object> global_data_freshness;
        static class Meta {
            public String  trained_at;
            public Integer samples;
            public Double  val_auc;
            public Double  val_mae_ret;
            public Double  val_mae_days;
        }
    }

    /**
     * ml-service 응답을 자유롭게 역직렬화하기 위한 내부 모델.
     * Python 측에서 snake_case로 보내더라도 Jackson이 기본적으로 camelCase와 혼용 가능하게
     * 주요 필드만 매핑. 알 수 없는 필드는 무시 (spring.jackson.default-property-inclusion).
     */
    public static class MlPredictResponse {
        public String ticker;
        public double confidence;
        public Double mlScore;
        public BigDecimal entryPrice;
        public BigDecimal takeProfitPrice;
        public BigDecimal stopLossPrice;
        public Integer expectedDays;
        public Integer maxDays;
        public String reason;
        public Map<String, Double> features;

        TradePlanDto toDto() {
            double score = (mlScore != null) ? mlScore : confidence * 100.0;
            int exp = (expectedDays != null) ? expectedDays : 5;
            int mx  = (maxDays != null) ? maxDays : 7;
            return new TradePlanDto(
                    ticker,
                    confidence,
                    score,
                    entryPrice,
                    takeProfitPrice,
                    stopLossPrice,
                    exp,
                    mx,
                    reason != null ? reason : String.format("ML conf=%.0f%%", confidence * 100),
                    features
            );
        }
    }
}
