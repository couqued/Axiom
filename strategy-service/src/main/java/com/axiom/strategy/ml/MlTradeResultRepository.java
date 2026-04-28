package com.axiom.strategy.ml;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MlTradeResultRepository extends JpaRepository<MlTradeResult, Long> {
    Page<MlTradeResult> findAllByOrderByExitAtDesc(Pageable pageable);

    interface ConfidenceTierProjection {
        String  getTier();
        Integer getTradeCount();
        Integer getWinCount();
        Double  getAvgReturnPct();
    }

    @Query(value = """
            SELECT
              CASE
                WHEN confidence < 0.80 THEN '0.75~0.80'
                WHEN confidence < 0.85 THEN '0.80~0.85'
                ELSE '0.85+'
              END                                       AS tier,
              COUNT(*)                                  AS trade_count,
              SUM(CASE WHEN is_win THEN 1 ELSE 0 END)  AS win_count,
              COALESCE(AVG(actual_return_pct), 0.0)     AS avg_return_pct
            FROM strategy.ml_trade_results
            WHERE confidence >= 0.75
            GROUP BY 1
            ORDER BY MIN(confidence)
            """, nativeQuery = true)
    List<ConfidenceTierProjection> findConfidenceTierStats();
}
