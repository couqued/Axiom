package com.axiom.market.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ServicesConfig {

    @Value("${services.portfolio.url:http://portfolio-service:8083}")
    private String portfolioServiceUrl;

    @Bean
    public WebClient portfolioWebClient() {
        return WebClient.builder().baseUrl(portfolioServiceUrl).build();
    }
}
