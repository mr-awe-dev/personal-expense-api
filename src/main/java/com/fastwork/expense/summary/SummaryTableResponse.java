package com.fastwork.expense.summary;

import java.time.LocalDate;
import java.util.List;

/**
 * Tabel summary dikelompokkan per kategori/sumber. `groups[]` selalu komple dan subtotal/totals
 * dihitung penuh via GROUP BY; hanya `rows[]` yang bisa terpotong (`truncated = true` bila jumlah
 * detail mencapai limit).
 */
public record SummaryTableResponse(
        LocalDate from,
        LocalDate to,
        List<TableGroup> groups,
        TableTotals totals,
        boolean truncated) {}
