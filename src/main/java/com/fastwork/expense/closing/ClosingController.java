package com.fastwork.expense.closing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fastwork.expense.user.CurrentUserService;
import com.fastwork.expense.user.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/closings")
@Tag(name = "Closings", description = "Tutup periode, riwayat arsip, unduh Excel, buka kembali")
public class ClosingController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final CurrentUserService currentUser;
    private final ClosingService closingService;

    @GetMapping
    @Operation(summary = "Riwayat arsip tutup periode")
    public List<ClosingRecord> list() {
        return closingService.list(currentUser.principal().getId());
    }

    @GetMapping("/preview")
    @Operation(
            summary =
                    "Pratinjau tutup periode; rentang default dari tanggal pertama-terakhir"
                            + " transaksi berjalan")
    public ClosingPreview preview(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        User user = currentUser.get();
        return closingService.preview(user.getId(), user.getTimezone(), from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tutup periode: arsipkan transaksi berjalan dalam rentang")
    public ClosingRecord close(@Valid @RequestBody ClosePeriodRequest request) {
        return closingService.close(currentUser.principal().getId(), request);
    }

    @GetMapping("/{id}/export.xlsx")
    @Operation(summary = "Unduh arsip Excel periode tutup")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType =
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> exportXlsx(@PathVariable UUID id) throws IOException {
        ClosingExport export = closingService.export(currentUser.principal().getId(), id);
        ContentDisposition disposition =
                ContentDisposition.attachment()
                        .filename(export.record().fileName(), StandardCharsets.UTF_8)
                        .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .body(ClosingExcelWriter.write(export));
    }

    @PostMapping("/{id}/reopen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Buka kembali: transaksi kembali berjalan, log arsip dihapus")
    public void reopen(@PathVariable UUID id) {
        closingService.reopen(currentUser.principal().getId(), id);
    }

    @DeleteMapping("/{id}/data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary =
                    "Hapus permanen data transaksi arsip; konfirmasi wajib melalui parameter"
                            + " confirm=HAPUS")
    public void deleteData(@PathVariable UUID id, @RequestParam String confirm) {
        closingService.deleteData(currentUser.principal().getId(), id, confirm);
    }
}
