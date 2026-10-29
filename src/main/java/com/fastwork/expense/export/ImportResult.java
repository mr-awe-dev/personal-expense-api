package com.fastwork.expense.export;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ImportResult")
public record ImportResult(int categories, int transactions, int budgets, int closings) {}
