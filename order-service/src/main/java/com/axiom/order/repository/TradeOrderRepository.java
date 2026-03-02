package com.axiom.order.repository;

import com.axiom.order.entity.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    List<TradeOrder> findAllByOrderByCreatedAtDesc();
    List<TradeOrder> findByTickerOrderByCreatedAtDesc(String ticker);
}
