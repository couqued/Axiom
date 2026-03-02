package com.axiom.market.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "candle")
public class CandleConfig {

    private List<String> watchTickers;
    private int defaultDays = 60;
}
