package com.fastwork.expense.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.CurrentUserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@Tag(name = "Export", description = "Backup JSON seluruh data dan import kembali")
public class ExportController {

    private static final long MAX_BODY_BYTES = 10L * 1024 * 1024;
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CurrentUserService currentUser;
    private final ExportService exportService;
    private final ImportService importService;
    private final ObjectMapper objectMapper;

    @GetMapping(value = "/export/backup.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Unduh backup seluruh data (JSON, termasuk transaksi arsip)")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BackupFile.class)))
    public ResponseEntity<byte[]> exportBackupJson() {
        BackupFile file = exportService.export(currentUser.principal().getId());
        String fileName = "backup_" + formatDate(file.exportedAt()) + ".json";
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(file);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Backup gagal diserialisasi", ex);
        }
        ContentDisposition disposition =
                ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Import data dari backup.json (merge idempoten; tidak menghapus data)",
            description = "Versi yang didukung: 1. Id kategori/transaksi/budget dipertahankan.")
    public ImportResult importData(
            HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestBody
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = BackupFile.class)))
                    String body) {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES
                || (contentLength < 0 && (long) body.length() * 2 > MAX_BODY_BYTES)) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "Ukuran berkas backup melebihi 10 MB");
        }
        BackupFile file;
        try {
            file = objectMapper.readValue(body, BackupFile.class);
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("MALFORMED_REQUEST", "Permintaan tidak dapat dibaca");
        }
        return importService.importFile(currentUser.principal().getId(), file);
    }

    private static String formatDate(Instant exportedAt) {
        return LocalDate.ofInstant(exportedAt, ZoneOffset.UTC).format(COMPACT);
    }
}
