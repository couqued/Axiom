package com.axiom.strategy.admin;

import java.math.BigDecimal;

public record TrailingStopStatusDto(BigDecimal peakPrice, BigDecimal stopPrice) {}
