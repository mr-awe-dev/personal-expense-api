package com.fastwork.expense.budget;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class BudgetPeriodConverter implements AttributeConverter<BudgetPeriod, String> {

    @Override
    public String convertToDatabaseColumn(BudgetPeriod attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public BudgetPeriod convertToEntityAttribute(String dbData) {
        return dbData == null ? null : BudgetPeriod.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
