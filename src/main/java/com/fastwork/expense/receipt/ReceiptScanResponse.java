package com.fastwork.expense.receipt;

import io.swagger.v3.oas.annotations.media.Schema;

/** Hasil pembacaan struk; field null berarti tidak terbaca dengan yakin. */
public record ReceiptScanResponse(
        @Schema(description = "Nominal rupiah terdeteksi", example = "45000") Long amount,
        @Schema(description = "Tanggal ISO terdeteksi", example = "2026-10-24") String date,
        @Schema(description = "Nama merchant/line pertama terdeteksi", example = "Warung Kopi")
                String description,
        @Schema(description = "Keyakinan heuristik 0–100", example = "80") int confidence,
        @Schema(
                        description =
                                "Teks OCR mentah — HANYA saat app.receipt.debug-return-ocr-text=true"
                                        + " (diagnostik non-prod)")
                String ocrText) {}
