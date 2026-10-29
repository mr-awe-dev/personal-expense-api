package com.fastwork.expense.receipt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fastwork.expense.common.ApiException;

import lombok.RequiredArgsConstructor;

/** Validasi berkas gambar → OCR (ekstraktor) → parse heuristik. Gambar TIDAK disimpan. */
@Service
@RequiredArgsConstructor
public class ReceiptScanService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptScanService.class);

    private final TextExtractor extractor;
    private final ReceiptProperties props;

    public ReceiptScanResponse scan(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("INVALID_IMAGE", "Berkas foto kosong");
        }
        if (file.getSize() > props.getMaxImageBytes()) {
            throw ApiException.badRequest(
                    "INVALID_IMAGE",
                    "Foto terlalu besar (maks "
                            + (props.getMaxImageBytes() / (1024 * 1024))
                            + " MB)");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw ApiException.badRequest("INVALID_IMAGE", "Berkas harus berupa gambar");
        }

        Path temp = null;
        Path processed = null;
        try {
            temp = Files.createTempFile("pet-receipt-", extensionFor(contentType));
            file.transferTo(temp);
            String text = extractor.extract(temp);
            if (text == null || text.isBlank()) {
                throw new ReceiptOcrException("teks OCR kosong");
            }
            ReceiptParser.Extracted parsed = ReceiptParser.parse(text);
            String bestText = text;
            try {
                processed = Files.createTempFile("pet-receipt-proc-", ".png");
                if (ReceiptImagePreprocessor.preprocess(temp, processed)) {
                    String procText = tryExtract(processed);
                    if (procText != null && !procText.isBlank()) {
                        ReceiptParser.Extracted procParsed = ReceiptParser.parse(procText);
                        if (procParsed.confidence() > parsed.confidence()) {
                            parsed = procParsed;
                            bestText = procText;
                        }
                    }
                }
            } catch (IOException ex) {
                log.debug("temp pra-pemrosesan gagal dibuat", ex);
            }
            return new ReceiptScanResponse(
                    parsed.amount(),
                    parsed.date() == null ? null : parsed.date().toString(),
                    parsed.merchant(),
                    parsed.confidence(),
                    props.isDebugReturnOcrText() ? bestText : null);
        } catch (ReceiptOcrException ex) {
            log.warn("OCR struk gagal: {}", ex.getMessage());
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECEIPT_UNREADABLE",
                    "Struk tidak terbaca. Pastikan foto tajam, tegak, dan seluruh nominal terlihat.");
        } catch (IOException ex) {
            log.error("IO saat scan struk", ex);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OCR_UNAVAILABLE",
                    "Layanan pembacaan struk tidak tersedia");
        } finally {
            deleteQuietly(temp);
            deleteQuietly(processed);
        }
    }

    private String tryExtract(Path image) {
        try {
            return extractor.extract(image);
        } catch (ReceiptOcrException ex) {
            log.debug("OCR hasil pra-pemrosesan gagal, coba gambar asli: {}", ex.getMessage());
            return null;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.debug("gagal menghapus temp struk", ex);
        }
    }

    private static String extensionFor(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
