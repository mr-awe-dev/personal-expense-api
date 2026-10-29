package com.fastwork.expense.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fastwork.expense.common.TxType;

class CategoryUnitTest {

    @Test
    void entityExposesEveryAccessorAndRename() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Category category = new Category(id, userId, TxType.EXPENSE, "Makanan", "makanan");

        assertThat(category.getId()).isEqualTo(id);
        assertThat(category.getUserId()).isEqualTo(userId);
        assertThat(category.getType()).isEqualTo(TxType.EXPENSE);
        assertThat(category.getName()).isEqualTo("Makanan");
        assertThat(category.getNameKey()).isEqualTo("makanan");
        assertThat(category.getArchived()).isFalse();
        assertThat(category.getCreatedAt()).isNull();
        assertThat(category.getUpdatedAt()).isNull();

        category.setArchived(Boolean.TRUE);
        assertThat(category.getArchived()).isTrue();

        category.rename("Kopi", "kopi");
        assertThat(category.getName()).isEqualTo("Kopi");
        assertThat(category.getNameKey()).isEqualTo("kopi");
    }
}
