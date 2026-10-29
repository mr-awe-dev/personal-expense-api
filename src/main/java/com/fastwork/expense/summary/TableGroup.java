package com.fastwork.expense.summary;

import java.util.List;
import java.util.UUID;

/**
 * Kelompok tabel per kategori (expense) atau sumber (income). `budgetAmount` hanya ada di kelompok
 * expense ber-budget; `difference` = subtotal − budget.
 */
public record TableGroup(
        UUID categoryId,
        String categoryName,
        String type,
        List<TableRow> rows,
        long subtotal,
        Long budgetAmount,
        Long difference) {}
