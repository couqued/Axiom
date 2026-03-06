package com.axiom.order.service;

import com.axiom.order.dto.SkippedSignalRequest;
import com.axiom.order.dto.SkippedSignalResponse;
import com.axiom.order.entity.SkippedSignal;
import com.axiom.order.repository.SkippedSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkippedSignalService {

    private final SkippedSignalRepository repo;

    /**
     * 같은 날 동일 ticker+skipReason 이면 skipCount 증가 + 최신가 업데이트.
     * 신규면 신규 레코드 생성.
     */
    @Transactional
    public void record(SkippedSignalRequest request) {
        LocalDate today = LocalDate.now();
        Optional<SkippedSignal> existing = repo.findByTradeDateAndTickerAndSkipReason(
                today, request.getTicker(), request.getSkipReason());

        if (existing.isPresent()) {
            SkippedSignal s = existing.get();
            s.setSkipCount(s.getSkipCount() + 1);
            s.setLastSkippedAt(LocalDateTime.now());
            s.setPrice(request.getPrice());
            repo.save(s);
        } else {
            LocalDateTime now = LocalDateTime.now();
            repo.save(SkippedSignal.builder()
                    .tradeDate(today)
                    .ticker(request.getTicker())
                    .stockName(request.getStockName())
                    .price(request.getPrice())
                    .strategyName(request.getStrategyName())
                    .marketState(request.getMarketState())
                    .skipReason(request.getSkipReason())
                    .skipCount(1)
                    .firstSkippedAt(now)
                    .lastSkippedAt(now)
                    .build());
            log.info("[SkippedSignal] 신규 기록 — ticker: {}, reason: {}", request.getTicker(), request.getSkipReason());
        }
    }

    /** 최근 N일 스킵 목록 (날짜 내림차순) */
    public List<SkippedSignalResponse> getRecent(int days) {
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        return repo.findByTradeDateBetweenOrderByTradeDateDescLastSkippedAtDesc(from, LocalDate.now())
                .stream()
                .map(SkippedSignalResponse::from)
                .toList();
    }
}
