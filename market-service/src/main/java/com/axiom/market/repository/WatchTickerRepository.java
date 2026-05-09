package com.axiom.market.repository;

import com.axiom.market.entity.WatchTicker;
import com.axiom.market.entity.WatchTicker.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WatchTickerRepository extends JpaRepository<WatchTicker, String> {

    List<WatchTicker> findByStatus(Status status);

    List<WatchTicker> findByStatusIn(Collection<Status> statuses);

    Optional<WatchTicker> findByTicker(String ticker);

    long countByStatus(Status status);
}
