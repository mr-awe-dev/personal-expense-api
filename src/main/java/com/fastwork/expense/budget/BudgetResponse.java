package com.fastwork.expense.budget;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Budget")
public record BudgetResponse(
        UUID categoryId,
        String categoryName,
        boolean categoryArchived,
        BudgetPeriod period,
        long amount,
        String description,
        Instant createdAt,
        Instant updatedAt) {}
