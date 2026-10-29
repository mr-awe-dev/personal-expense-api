package com.fastwork.expense.closing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

public final class ClosingExcelWriter {

    private static final int HEADER_ROW = 5;
    private static final int COLS = 7;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] HEADERS =
            new String[] {
                "Tanggal", "Jenis", "Kategori", "Keterangan", "Jumlah", "Budget", "Selisih"
            };

    private ClosingExcelWriter() {}

    public static byte[] write(ClosingExport export) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try {
            SXSSFSheet sheet = workbook.createSheet("Arsip");
            CellStyle bold = boldStyle(workbook);
            CellStyle money = numberStyle(workbook, "#,##0");
            CellStyle dateStyle = numberStyle(workbook, "dd/mm/yyyy");

            ClosingRecord record = export.record();
            label(sheet, 0, 0, "Periode", bold);
            label(
                    sheet,
                    0,
                    1,
                    DAY.format(record.periodStart()) + " - " + DAY.format(record.periodEnd()),
                    null);
            label(sheet, 1, 0, "Total Pemasukan", null);
            number(sheet, 1, 1, record.totalIncome(), money);
            label(sheet, 2, 0, "Total Pengeluaran", null);
            number(sheet, 2, 1, record.totalExpense(), money);
            label(sheet, 3, 0, "Selisih", null);
            number(sheet, 3, 1, record.net(), money);

            for (int col = 0; col < COLS; col++) {
                label(sheet, HEADER_ROW, col, HEADERS[col], bold);
            }

            int row = HEADER_ROW + 1;
            for (ClosingExport.ExportRow item : export.rows()) {
                Row excelRow = sheet.createRow(row);
                Cell dateCell = excelRow.createCell(0);
                dateCell.setCellValue(item.date());
                dateCell.setCellStyle(dateStyle);
                label(sheet, row, 1, jenisOf(item.type()), null);
                label(
                        sheet,
                        row,
                        2,
                        item.categoryName() == null ? "-" : safe(item.categoryName()),
                        null);
                label(sheet, row, 3, safe(item.description()), null);
                number(sheet, row, 4, item.amount(), money);
                row++;
            }
            int lastDataRow = export.rows().isEmpty() ? HEADER_ROW : row - 1;

            for (ClosingExport.CategorySubtotal subtotal : export.subtotals()) {
                label(
                        sheet,
                        row,
                        2,
                        subtotal.categoryName() == null ? "-" : safe(subtotal.categoryName()),
                        bold);
                label(sheet, row, 3, "Subtotal", bold);
                number(sheet, row, 4, subtotal.subtotal(), money);
                if (subtotal.budgetAmount() != null) {
                    number(sheet, row, 5, subtotal.budgetAmount(), money);
                }
                if (subtotal.difference() != null) {
                    number(sheet, row, 6, subtotal.difference(), money);
                }
                row++;
            }

            label(sheet, row, 3, "Grand Total", bold);
            long grandAmounts = 0;
            Long grandBudget = null;
            Long grandDifference = null;
            for (ClosingExport.CategorySubtotal subtotal : export.subtotals()) {
                grandAmounts += subtotal.subtotal();
                if (subtotal.budgetAmount() != null) {
                    grandBudget = (grandBudget == null ? 0 : grandBudget) + subtotal.budgetAmount();
                    grandDifference =
                            (grandDifference == null ? 0 : grandDifference) + subtotal.difference();
                }
            }
            number(sheet, row, 4, grandAmounts, money);
            if (grandBudget != null) {
                number(sheet, row, 5, grandBudget, money);
                number(sheet, row, 6, grandDifference, money);
            }

            sheet.createFreezePane(0, HEADER_ROW + 1);
            if (!export.rows().isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, lastDataRow, 0, COLS - 1));
            }
            widths(sheet);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private static void widths(SXSSFSheet sheet) {
        int[] widths = new int[] {12, 12, 28, 36, 16, 16, 16};
        for (int col = 0; col < widths.length; col++) {
            sheet.setColumnWidth(col, widths[col] * 256);
        }
    }

    private static String jenisOf(String type) {
        return "INCOME".equals(type) ? "pemasukan" : "pengeluaran";
    }

    private static String safe(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        if (first == '='
                || first == '+'
                || first == '-'
                || first == '@'
                || first == '\t'
                || first == '\r') {
            return "'" + text;
        }
        return text;
    }

    private static CellStyle boldStyle(SXSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private static CellStyle numberStyle(SXSSFWorkbook workbook, String pattern) {
        DataFormat format = workbook.createDataFormat();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(format.getFormat(pattern));
        return style;
    }

    private static void label(SXSSFSheet sheet, int row, int col, String text, CellStyle style) {
        Cell cell = row(sheet, row).createCell(col);
        String value = text == null || text.isEmpty() ? null : text;
        if (value != null) {
            cell.setCellValue(value);
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void number(SXSSFSheet sheet, int row, int col, long value, CellStyle style) {
        Cell cell = row(sheet, row).createCell(col);
        cell.setCellValue((double) value);
        cell.setCellStyle(style);
    }

    private static Row row(SXSSFSheet sheet, int index) {
        Row created = sheet.getRow(index);
        return created != null ? created : sheet.createRow(index);
    }
}
