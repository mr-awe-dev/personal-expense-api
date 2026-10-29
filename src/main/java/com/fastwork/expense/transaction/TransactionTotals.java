package com.fastwork.expense.transaction;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TransactionTotals")
public record TransactionTotals(long income, long expense, long net) {

    public static TransactionTotals of(long income, long expense) {
        return new TransactionTotals(income, expense, income - expense);
    }
}
