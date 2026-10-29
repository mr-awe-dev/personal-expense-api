package com.fastwork.expense.summary;

import java.util.UUID;

/**
 * Budget vs aktual dalam rentang. Budget harian diskalakan `amount * jumlah-hari-rentang`; bulanan
 * dibandingkan langsung ke aktual rentang.
 */
public record BudgetActualRow(
        UUID categoryId,
        String categoryName,
        boolean categoryArchived,
        String period,
        long budgetAmount,
        long spent,
        long remaining) {}
