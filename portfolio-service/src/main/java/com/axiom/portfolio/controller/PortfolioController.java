package com.axiom.portfolio.controller;

import com.axiom.portfolio.dto.PortfolioItemDto;
import com.axiom.portfolio.service.KisAccountApiService;
import com.axiom.portfolio.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final KisAccountApiService kisAccountApiService;

    // 보유 주식 현황: GET /api/portfolio
    @GetMapping
    public ResponseEntity<List<PortfolioItemDto>> getPortfolio() {
        return ResponseEntity.ok(portfolioService.getAll());
    }

    // 계좌 잔고: GET /api/portfolio/balance
    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance() {
        return ResponseEntity.ok(kisAccountApiService.getBalance());
    }
}
