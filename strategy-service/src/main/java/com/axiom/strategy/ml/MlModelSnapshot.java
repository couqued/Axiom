package com.axiom.strategy.ml;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(schema = "strategy", name = "ml_model_snapshots")
@Getter
@NoArgsConstructor
public class MlModelSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String trainedAt;

    private Integer samples;

    private Double valAuc;

    private Double valMaeRet;

    private Double valMaeDays;

    @Column(nullable = false)
    private LocalDateTime recordedAt;

    public MlModelSnapshot(String trainedAt, Integer samples, Double valAuc,
                           Double valMaeRet, Double valMaeDays) {
        this.trainedAt  = trainedAt;
        this.samples    = samples;
        this.valAuc     = valAuc;
        this.valMaeRet  = valMaeRet;
        this.valMaeDays = valMaeDays;
        this.recordedAt = LocalDateTime.now();
    }
}
