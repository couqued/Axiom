package com.axiom.market.repository;

import com.axiom.market.entity.InvestorFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InvestorFlowRepository extends JpaRepository<InvestorFlow, Long> {

    List<InvestorFlow> findByTickerAndTradeDateBetweenOrderByTradeDateAsc(
            String ticker, LocalDate from, LocalDate to);

    Optional<InvestorFlow> findByTickerAndTradeDate(String ticker, LocalDate tradeDate);
}
