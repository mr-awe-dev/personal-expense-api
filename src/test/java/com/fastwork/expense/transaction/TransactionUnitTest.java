package com.fastwork.expense.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fastwork.expense.common.TxType;

class TransactionUnitTest {

    @Test
    void entityConstructorAndMutators() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Transaction tx =
                new Transaction(id, userId, LocalDate.of(2026, 10, 20), TxType.EXPENSE, 15_000L);

        assertThat(tx.getId()).isEqualTo(id);
        assertThat(tx.getUserId()).isEqualTo(userId);
        assertThat(tx.getDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(tx.getType()).isEqualTo(TxType.EXPENSE);
        assertThat(tx.getAmount()).isEqualTo(15_000L);
        assertThat(tx.getDescription()).isEmpty();
        assertThat(tx.getCategoryId()).isNull();
        assertThat(tx.getClosingId()).isNull();
        assertThat(tx.getCreatedAt()).isNull();
        assertThat(tx.getUpdatedAt()).isNull();

        tx.setDate(LocalDate.of(2026, 10, 21));
        tx.setType(TxType.INCOME);
        tx.setCategoryId(UUID.randomUUID());
        tx.setDescription("gaji lembur");
        tx.setAmount(200_000L);

        assertThat(tx.getDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(tx.getType()).isEqualTo(TxType.INCOME);
        assertThat(tx.getCategoryId()).isNotNull();
        assertThat(tx.getDescription()).isEqualTo("gaji lembur");
        assertThat(tx.getAmount()).isEqualTo(200_000L);
    }

    @Test
    void totalsComputeNetAndDeltas() {
        TransactionTotals totals = TransactionTotals.of(100_000L, 40_000L);
        assertThat(totals.net()).isEqualTo(60_000L);
        assertThat(totals.income()).isEqualTo(100_000L);
        assertThat(totals.expense()).isEqualTo(40_000L);
    }

    @Test
    void allSortValuesAreWhitelisted() {
        assertThat(TransactionSort.values())
                .extracting(Enum::name)
                .contains(
                        "DATE_DESC",
                        "DATE_ASC",
                        "AMOUNT_DESC",
                        "AMOUNT_ASC",
                        "CATEGORY_ASC",
                        "CATEGORY_DESC");
    }
}
