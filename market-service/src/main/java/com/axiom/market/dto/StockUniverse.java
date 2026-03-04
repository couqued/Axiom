package com.axiom.market.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

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
}
