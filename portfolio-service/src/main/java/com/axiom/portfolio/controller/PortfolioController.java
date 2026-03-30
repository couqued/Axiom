package com.axiom.portfolio.controller;

import com.axiom.portfolio.dto.PortfolioItemDto;
import com.axiom.portfolio.service.KisAccountApiService;
import com.axiom.portfolio.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final KisAccountApiService kisAccountApiService;

    @GetMapping("/api/portfolio")
    public ResponseEntity<List<PortfolioItemDto>> getPortfolio() {
        return ResponseEntity.ok(portfolioService.getAll());
    }

    @GetMapping("/api/portfolio/balance")
    public ResponseEntity<Map<String, Object>> getBalance() {
        return ResponseEntity.ok(kisAccountApiService.getBalance());
    }

    @DeleteMapping("/api/portfolio/admin/by-ticker")
    public ResponseEntity<Void> deleteByTicker(@RequestParam String ticker) {
        portfolioService.deletePosition(ticker);
        return ResponseEntity.noContent().build();
    }
}
