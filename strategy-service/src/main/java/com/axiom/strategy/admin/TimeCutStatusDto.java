package com.axiom.strategy.admin;

import java.time.LocalDate;

public record TimeCutStatusDto(LocalDate buyDate, int elapsed, int remaining) {}
