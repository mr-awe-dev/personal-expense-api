package com.fastwork.expense.closing;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ClosingPreview")
public record ClosingPreview(
        LocalDate from,
        LocalDate to,
        long txCount,
        long totalIncome,
        long totalExpense,
        long net) {}
