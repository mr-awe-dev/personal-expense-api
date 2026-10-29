package com.fastwork.expense.closing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

/** Semua cabang writer Excel diuji langsung lewat objek ClosingExport. */
class ClosingExcelWriterTest {

    private static ClosingRecord record() {
        return new ClosingRecord(
                UUID.randomUUID(),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 3),
                1_000_000L,
                250_000L,
                750_000L,
                4,
                "closing_20261001-20261003.xlsx",
                Instant.parse("2026-10-03T10:00:00Z"));
    }

    private static ClosingExport.ExportRow row(
            String type, UUID categoryId, String categoryName, String description, long amount) {
        return new ClosingExport.ExportRow(
                UUID.randomUUID(),
                LocalDate.of(2026, 10, 2),
                type,
                categoryId,
                categoryName,
                description,
                amount);
    }

    @Test
    void emptyExportStillProducesMetaAndGrandTotal() throws Exception {
        byte[] bytes = ClosingExcelWriter.write(new ClosingExport(record(), List.of(), List.of()));
        assertThat(bytes).isNotEmpty();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Arsip");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Total Pemasukan");
            assertThat(sheet.getRow(6).getCell(3).getStringCellValue()).isEqualTo("Grand Total");
        }
    }

    @Test
    void allCellVariantsAreEscaped() throws Exception {
        UUID category = UUID.randomUUID();
        ClosingExport export =
                new ClosingExport(
                        record(),
                        List.of(
                                row("INCOME", category, "Gaji", null, 1_000_000L),
                                row("EXPENSE", null, null, "", 10L),
                                row("EXPENSE", category, "Makan", "=SUM(A1)", 20L),
                                row("EXPENSE", category, "Makan", "+123", 30L),
                                row("EXPENSE", category, "Makan", "-45", 40L),
                                row("EXPENSE", category, "Makan", "@cmd", 50L),
                                row("EXPENSE", category, "Makan", "\ttab", 60L),
                                row("EXPENSE", category, "Makan", "\rcr", 70L),
                                row("EXPENSE", category, "Makan", "normal", 80L)),
                        List.of(
                                new ClosingExport.CategorySubtotal(
                                        null, null, "EXPENSE", 10L, null, null),
                                new ClosingExport.CategorySubtotal(
                                        category, "Makan", "EXPENSE", 350L, 500L, -150L),
                                new ClosingExport.CategorySubtotal(
                                        category, "Makan", "EXPENSE", 100L, 200L, -100L),
                                new ClosingExport.CategorySubtotal(
                                        category, "Gaji", "INCOME", 1_000_000L, null, null)));

        byte[] bytes = ClosingExcelWriter.write(export);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Arsip");
            assertThat(sheet.getRow(6).getCell(1).getStringCellValue()).isEqualTo("pemasukan");
            assertThat(sheet.getRow(7).getCell(1).getStringCellValue()).isEqualTo("pengeluaran");
            assertThat(sheet.getRow(7).getCell(2).getStringCellValue()).isEqualTo("-");
        }
    }
}
