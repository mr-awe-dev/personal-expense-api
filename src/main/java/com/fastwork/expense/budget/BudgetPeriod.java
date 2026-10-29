package com.fastwork.expense.budget;

public enum BudgetPeriod {
    DAILY,
    MONTHLY;

    public boolean isDaily() {
        return this == DAILY;
    }
}
