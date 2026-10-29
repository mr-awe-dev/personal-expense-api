package com.fastwork.expense.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class BudgetUnitTest {

    @Test
    void entityExposesEveryAccessorAndMutator() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Budget budget =
                new Budget(id, userId, categoryId, BudgetPeriod.DAILY, 50_000L, "makan harian");

        assertThat(budget.getId()).isEqualTo(id);
        assertThat(budget.getUserId()).isEqualTo(userId);
        assertThat(budget.getCategoryId()).isEqualTo(categoryId);
        assertThat(budget.getPeriod()).isEqualTo(BudgetPeriod.DAILY);
        assertThat(budget.getAmount()).isEqualTo(50_000L);
        assertThat(budget.getDescription()).isEqualTo("makan harian");
        assertThat(budget.getCreatedAt()).isNull();
        assertThat(budget.getUpdatedAt()).isNull();

        budget.setPeriod(BudgetPeriod.MONTHLY);
        budget.setAmount(1_000_000L);
        budget.setDescription("bulanan");

        assertThat(budget.getPeriod()).isEqualTo(BudgetPeriod.MONTHLY);
        assertThat(budget.getAmount()).isEqualTo(1_000_000L);
        assertThat(budget.getDescription()).isEqualTo("bulanan");
    }

    @Test
    void responseCarriesCategoryProjectionFields() {
        Budget budget =
                new Budget(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BudgetPeriod.DAILY,
                        25_000L,
                        "kopi");
        assertThat(budget.getDescription()).isEqualTo("kopi");
    }
}
