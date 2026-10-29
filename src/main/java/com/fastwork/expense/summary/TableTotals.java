package com.fastwork.expense.summary;

/** Grand total tabel summary (net = income − expense). */
public record TableTotals(long income, long expense, long net) {}
