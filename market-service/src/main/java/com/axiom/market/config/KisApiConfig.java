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

    private String mode; // mock | paper | real
    private ModeConfig paper;
    private ModeConfig real;

    public ModeConfig getActive() {
        return "real".equals(mode) ? real : paper;
    }

    public boolean isMock()  { return "mock".equals(mode); }
    public boolean isPaper() { return "paper".equals(mode); }
    public boolean isReal()  { return "real".equals(mode); }

    @Bean
    public WebClient kisWebClient() {
        if (isMock()) {
            return WebClient.builder().baseUrl("http://localhost").build();
        }
        return WebClient.builder()
                .baseUrl(getActive().getBaseUrl())
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
