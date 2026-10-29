package com.fastwork.expense.closing;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ClosePeriodRequest")
public record ClosePeriodRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}
