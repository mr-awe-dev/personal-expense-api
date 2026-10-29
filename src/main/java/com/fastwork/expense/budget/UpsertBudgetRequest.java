package com.fastwork.expense.budget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpsertBudgetRequest")
public record UpsertBudgetRequest(
        @NotNull BudgetPeriod period,
        @NotNull @Min(0) @Max(999999999999L) Long amount,
        @Size(max = 200) String description) {}
