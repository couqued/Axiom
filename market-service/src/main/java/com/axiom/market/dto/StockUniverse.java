package com.axiom.market.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * stock-universe.json 역직렬화 DTO.
 */
@Getter
@NoArgsConstructor
public class StockUniverse {
    private String description;
    private String lastUpdated;
    private List<String> kospi200;
    private List<String> kosdaq150;
    private Map<String, String> tickerNames;
}
