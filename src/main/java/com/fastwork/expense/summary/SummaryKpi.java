package com.fastwork.expense.summary;

/**
 * KPI ringkas. `savingsRate` = net/income (persen, bisa negatif) dihilangkan (null) bila income 0 —
 * pembagian nol tidak pernah dihitung.
 */
public record SummaryKpi(long totalIncome, long totalExpense, long net, Long savingsRate) {}
