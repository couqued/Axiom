package com.axiom.strategy.ml;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlTradeResultRepository extends JpaRepository<MlTradeResult, Long> {
    Page<MlTradeResult> findAllByOrderByExitAtDesc(Pageable pageable);
}
