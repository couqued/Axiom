package com.axiom.order.repository;

import com.axiom.order.entity.SkippedSignal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SkippedSignalRepository extends JpaRepository<SkippedSignal, Long> {

    Optional<SkippedSignal> findByTradeDateAndTickerAndSkipReason(
            LocalDate tradeDate, String ticker, String skipReason);

    List<SkippedSignal> findByTradeDateBetweenOrderByTradeDateDescLastSkippedAtDesc(
            LocalDate from, LocalDate to);
}
