package com.axiom.strategy.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "strategy_state", schema = "strategy",
    uniqueConstraints = @UniqueConstraint(columnNames = {"type", "ticker"})
)
@Getter
@Setter
@NoArgsConstructor
public class StrategyState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String type;   // "PEAK_PRICE" | "BUY_DATE" | "TODAY_BOUGHT"

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(nullable = false, length = 100)
    private String value;  // BigDecimal.toPlainString() | LocalDate.toString()

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public StrategyState(String type, String ticker, String value) {
        this.type = type;
        this.ticker = ticker;
        this.value = value;
    }
}
