package com.axiom.portfolio.repository;

import com.axiom.portfolio.entity.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {
    Optional<Portfolio> findByTickerAndTradingMode(String ticker, String tradingMode);
    List<Portfolio> findByTradingMode(String tradingMode);
}
