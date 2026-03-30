package com.axiom.market.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kis")
public class KisApiConfig {

    private ModeConfig real;

    @Bean
    public WebClient kisWebClient() {
        return WebClient.builder()
                .baseUrl(real.getBaseUrl())
                .build();
    }

    @Getter
    @Setter
    public static class ModeConfig {
        private String baseUrl;
        private String appKey;
        private String appSecret;
        private String accountNo;
    }
}
