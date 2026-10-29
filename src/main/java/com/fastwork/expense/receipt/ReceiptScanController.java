package com.fastwork.expense.receipt;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/receipts")
@Tag(name = "Receipts", description = "Pembacaan struk dari foto (OCR), gambar tidak disimpan")
public class ReceiptScanController {

    private final ReceiptScanService scanService;

    @PostMapping(value = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Scan foto struk → nominal, tanggal, merchant",
            description =
                    "Upload multipart field `file` (gambar ≤ 10 MB). Server menjalankan OCR lalu mengembalikan hasil parse heuristik; berkas tidak dipersistenkan.")
    public ReceiptScanResponse scan(@RequestPart("file") MultipartFile file) {
        return scanService.scan(file);
    }
}
