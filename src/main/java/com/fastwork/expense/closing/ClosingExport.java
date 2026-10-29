package com.fastwork.expense.closing;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ClosingExport(
        ClosingRecord record, List<ExportRow> rows, List<CategorySubtotal> subtotals) {

    public record ExportRow(
            UUID id,
            LocalDate date,
            String type,
            UUID categoryId,
            String categoryName,
            String description,
            long amount) {}

    public record CategorySubtotal(
            UUID categoryId,
            String categoryName,
            String type,
            long subtotal,
            Long budgetAmount,
            Long difference) {}
}
