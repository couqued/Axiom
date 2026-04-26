package com.axiom.strategy.ml;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlModelSnapshotRepository extends JpaRepository<MlModelSnapshot, Long> {
    boolean existsByTrainedAt(String trainedAt);
    Optional<MlModelSnapshot> findTopByOrderByRecordedAtDesc();
}
