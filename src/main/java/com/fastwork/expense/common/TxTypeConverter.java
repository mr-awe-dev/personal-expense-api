package com.fastwork.expense.common;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TxTypeConverter implements AttributeConverter<TxType, String> {

    @Override
    public String convertToDatabaseColumn(TxType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public TxType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TxType.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
