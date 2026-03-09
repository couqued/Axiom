package com.axiom.strategy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyStateRepository extends JpaRepository<StrategyState, Long> {
    List<StrategyState> findAllByType(String type);
    Optional<StrategyState> findByTypeAndTicker(String type, String ticker);
    void deleteByTypeAndTicker(String type, String ticker);
}
