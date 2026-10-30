package com.fastwork.expense.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fastwork.expense.budget.BudgetPeriod;
import com.fastwork.expense.budget.BudgetPeriodConverter;
import com.fastwork.expense.user.UserRole;
import com.fastwork.expense.user.UserRoleConverter;

/** Konverter enum ↔ kolom PostgreSQL: kedua sisi null dan non-null. */
class ConvertersTest {

    @Test
    void txTypeConverterMapsBothDirectionsAndNulls() {
        TxTypeConverter converter = new TxTypeConverter();
        assertThat(converter.convertToDatabaseColumn(TxType.INCOME)).isEqualTo("income");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute("expense")).isEqualTo(TxType.EXPENSE);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void userRoleConverterMapsBothDirectionsAndNulls() {
        UserRoleConverter converter = new UserRoleConverter();
        assertThat(converter.convertToDatabaseColumn(UserRole.OWNER)).isEqualTo("owner");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute("owner")).isEqualTo(UserRole.OWNER);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void budgetPeriodConverterMapsBothDirectionsAndNulls() {
        BudgetPeriodConverter converter = new BudgetPeriodConverter();
        assertThat(converter.convertToDatabaseColumn(BudgetPeriod.DAILY)).isEqualTo("daily");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute("monthly")).isEqualTo(BudgetPeriod.MONTHLY);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void budgetPeriodIsDailyCoversBothValues() {
        assertThat(BudgetPeriod.DAILY.isDaily()).isTrue();
        assertThat(BudgetPeriod.MONTHLY.isDaily()).isFalse();
        assertThat(BudgetPeriod.valueOf("MONTHLY")).isEqualTo(BudgetPeriod.MONTHLY);
        assertThat(BudgetPeriod.values()).hasSize(2);
    }

    @Test
    void txTypeIsIncomeCoversBothValues() {
        assertThat(TxType.INCOME.isIncome()).isTrue();
        assertThat(TxType.EXPENSE.isIncome()).isFalse();
        assertThat(TxType.valueOf("EXPENSE")).isEqualTo(TxType.EXPENSE);
    }

    @Test
    void apiErrorFactoriesNormalizeNullAndEmptyLists() {
        assertThat(ApiError.of("CODE", "pesan").fieldErrors()).isNull();
        assertThat(ApiError.of("CODE", "pesan", java.util.List.of()).fieldErrors()).isNull();
        assertThat(
                        ApiError.of(
                                        "CODE",
                                        "pesan",
                                        java.util.List.of(new ApiError.FieldError("f", "m")))
                                .fieldErrors())
                .singleElement()
                .satisfies(
                        error -> {
                            assertThat(error.field()).isEqualTo("f");
                            assertThat(error.message()).isEqualTo("m");
                        });
        assertThat(ApiError.of("CODE", "pesan", null).fieldErrors()).isNull();
    }

    @Test
    void apiExceptionFactoriesCarryStatusAndCode() {
        assertThat(ApiException.notFound("a").status())
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(ApiException.conflict("a").code()).isEqualTo("CONFLICT");
        assertThat(ApiException.unauthorized("a").code()).isEqualTo("UNAUTHORIZED");
        assertThat(ApiException.unprocessable("a").status())
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ApiException.badRequest("KODE", "a").code()).isEqualTo("KODE");
        assertThat(ApiException.rateLimited(9, "a").retryAfterSeconds()).isEqualTo(9L);
        assertThat(
                        new ApiException(org.springframework.http.HttpStatus.GONE, "X", "m")
                                .retryAfterSeconds())
                .isNull();
    }
}
