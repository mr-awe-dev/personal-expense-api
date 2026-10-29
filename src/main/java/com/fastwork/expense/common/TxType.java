package com.fastwork.expense.common;

public enum TxType {
    INCOME,
    EXPENSE;

    public boolean isIncome() {
        return this == INCOME;
    }
}
