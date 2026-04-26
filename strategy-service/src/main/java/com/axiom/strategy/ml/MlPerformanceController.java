package com.axiom.strategy.ml;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/strategy/ml-performance")
@RequiredArgsConstructor
public class MlPerformanceController {

    private final MlPerformanceService performanceService;

    @GetMapping("/summary")
    public MlSummaryDto getSummary() {
        return performanceService.getSummary();
    }

    @GetMapping("/trades")
    public Page<MlTradeResult> getTradeHistory(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return performanceService.getTradeHistory(page, Math.min(size, 100));
    }
}
