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

    @Value("${market-service.url}")
    private String marketServiceUrl;

    @Value("${order-service.url}")
    private String orderServiceUrl;

    @Bean
    public WebClient marketWebClient() {
        return WebClient.builder().baseUrl(marketServiceUrl).build();
    }

    @Bean
    public WebClient orderWebClient() {
        return WebClient.builder().baseUrl(orderServiceUrl).build();
    }
}
