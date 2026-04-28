package com.axiom.market.repository;

import com.axiom.market.entity.DailyCandle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyCandleRepository extends JpaRepository<DailyCandle, Long> {

    List<DailyCandle> findByTickerAndTradeDateBetweenOrderByTradeDateAsc(
            String ticker, LocalDate from, LocalDate to);

    Optional<DailyCandle> findByTickerAndTradeDate(String ticker, LocalDate tradeDate);

    Optional<DailyCandle> findTopByTickerOrderByTradeDateDesc(String ticker);

    @Query("SELECT MAX(c.tradeDate) FROM DailyCandle c")
    Optional<LocalDate> findLatestTradeDate();
}
