package com.fastwork.expense.closing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ClosingRecord")
public record ClosingRecord(
        UUID id,
        LocalDate periodStart,
        LocalDate periodEnd,
        long totalIncome,
        long totalExpense,
        long net,
        int txCount,
        String fileName,
        Instant closedAt) {}
