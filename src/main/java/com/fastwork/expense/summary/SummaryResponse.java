package com.fastwork.expense.summary;

import java.time.LocalDate;
import java.util.List;

/**
 * Ringkasan periode: KPI + per sumber income + per kategori expense + budget-vs-aktual + tren
 * harian. Semua agregasi di SQL; hanya transaksi berjalan (`closing_id null`) yang dihitung.
 */
public record SummaryResponse(
        LocalDate from,
        LocalDate to,
        SummaryKpi kpi,
        List<CategoryShare> incomeBySource,
        List<CategoryShare> expenseByCategory,
        List<BudgetActualRow> budgetVsActual,
        List<TrendPoint> trend) {}
