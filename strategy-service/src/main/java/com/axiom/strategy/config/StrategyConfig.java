package com.axiom.strategy.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "strategy")
public class StrategyConfig {

    private List<String> watchTickers;
    private int candleDays = 60;
    private int orderQuantity = 1;
    private List<String> enabledStrategies;

    private MarketFilterConfig marketFilter = new MarketFilterConfig();
    private TrailingStopConfig trailingStop = new TrailingStopConfig();
    private TimeCutConfig timeCut = new TimeCutConfig();

    @Value("${market-service.url}")
    private String marketServiceUrl;

    @Value("${order-service.url}")
    private String orderServiceUrl;

    @Value("${portfolio-service.url}")
    private String portfolioServiceUrl;

    @Bean
    public WebClient marketWebClient() {
        return WebClient.builder().baseUrl(marketServiceUrl).build();
    }

    @Bean
    public WebClient orderWebClient() {
        return WebClient.builder().baseUrl(orderServiceUrl).build();
    }

    @Bean
    public WebClient portfolioWebClient() {
        return WebClient.builder().baseUrl(portfolioServiceUrl).build();
    }

    // ── 중첩 설정 클래스 ─────────────────────────────────────────────────────

    /** 시장 필터 설정 (코스피 20일 MA 기준 상승장/횡보장 판별) */
    @Getter
    @Setter
    public static class MarketFilterConfig {
        /** true: 시장 상태에 따라 전략 선택, false: 모든 전략 실행 */
        private boolean enabled = true;
        /** 기준 지수 코드 ("0001" = 코스피, "1001" = 코스닥) */
        private String indexCode = "0001";
        /** 이동평균 기간 (기본 20일) */
        private int maPeriod = 20;
    }

    /** 트레일링 스탑 설정 */
    @Getter
    @Setter
    public static class TrailingStopConfig {
        /** true: 트레일링 스탑 활성화 */
        private boolean enabled = true;
        /** 고점 대비 하락 허용 비율 (기본 7%) */
        private double stopPercent = 7.0;
    }

    /** 타임 컷 설정 */
    @Getter
    @Setter
    public static class TimeCutConfig {
        /** true: 타임 컷 활성화 */
        private boolean enabled = true;
        /** 최대 보유 거래일 수 (기본 3거래일) */
        private int maxHoldingDays = 3;
        /** 타임 컷 대상 전략 목록 (해당 전략의 BUY 신호에만 적용) */
        private List<String> applicableStrategies = List.of("rsi-bollinger");
    }
}
