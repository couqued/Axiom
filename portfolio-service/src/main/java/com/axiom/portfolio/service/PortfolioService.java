package com.axiom.portfolio.service;

import com.axiom.portfolio.dto.PortfolioItemDto;
import com.axiom.portfolio.entity.Portfolio;
import com.axiom.portfolio.repository.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;

    public List<PortfolioItemDto> getAll() {
        return portfolioRepository.findAll().stream()
                .map(PortfolioItemDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 매수 체결 시 포트폴리오 추가/업데이트
     * 평균 단가 = (기존 총투자금 + 신규 투자금) / 전체 수량
     */
    @Transactional
    public void addPosition(String ticker, String stockName, int quantity, BigDecimal price) {
        Optional<Portfolio> existing = portfolioRepository.findByTicker(ticker);
        BigDecimal newInvest = price.multiply(BigDecimal.valueOf(quantity));

        if (existing.isPresent()) {
            Portfolio p = existing.get();
            int newQuantity = p.getQuantity() + quantity;
            BigDecimal newTotalInvest = p.getTotalInvest().add(newInvest);
            BigDecimal newAvgPrice = newTotalInvest.divide(BigDecimal.valueOf(newQuantity), 2, RoundingMode.HALF_UP);

            p.setQuantity(newQuantity);
            p.setTotalInvest(newTotalInvest);
            p.setAvgPrice(newAvgPrice);
            portfolioRepository.save(p);
            log.info("포트폴리오 업데이트 (매수) - ticker: {}, qty: {}, avgPrice: {}", ticker, newQuantity, newAvgPrice);
        } else {
            Portfolio p = Portfolio.builder()
                    .ticker(ticker)
                    .stockName(stockName)
                    .quantity(quantity)
                    .avgPrice(price)
                    .totalInvest(newInvest)
                    .build();
            portfolioRepository.save(p);
            log.info("포트폴리오 신규 추가 - ticker: {}, qty: {}, avgPrice: {}", ticker, quantity, price);
        }
    }

    /**
     * 매도 체결 시 포트폴리오 수량 차감
     * 전량 매도 시 종목 삭제
     */
    @Transactional
    public void reducePosition(String ticker, int quantity, BigDecimal price) {
        portfolioRepository.findByTicker(ticker).ifPresent(p -> {
            int remaining = p.getQuantity() - quantity;
            if (remaining <= 0) {
                portfolioRepository.delete(p);
                log.info("포트폴리오 종목 삭제 (전량 매도) - ticker: {}", ticker);
            } else {
                BigDecimal soldAmount = p.getAvgPrice().multiply(BigDecimal.valueOf(quantity));
                p.setQuantity(remaining);
                p.setTotalInvest(p.getTotalInvest().subtract(soldAmount));
                portfolioRepository.save(p);
                log.info("포트폴리오 업데이트 (매도) - ticker: {}, remainQty: {}", ticker, remaining);
            }
        });
    }
}
