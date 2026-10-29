package com.fastwork.expense.today;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TodayCategory")
public record TodayCategory(
        UUID categoryId,
        String categoryName,
        boolean categoryArchived,
        long limitAmount,
        long spent,
        long remaining) {}
