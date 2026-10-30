package com.fastwork.expense.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.CurrentUserService;
import com.fastwork.expense.user.PetPrincipal;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRole;

/** Unit test controller export/import + validasi versi service — jalur error tanpa DB. */
class ExportImportUnitTest {

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ExportService exportService = mock(ExportService.class);
    private final ImportService importService = mock(ImportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final UUID userId = UUID.randomUUID();

    private ExportController controller(ObjectMapper mapper) {
        when(currentUser.principal())
                .thenReturn(new PetPrincipal(new User(userId, "a@b.c", "hash", UserRole.OWNER)));
        return new ExportController(currentUser, exportService, importService, mapper);
    }

    private BackupFile sampleFile() {
        return new BackupFile(
                1, Instant.parse("2026-10-24T00:00:00Z"), null, null, null, null, null);
    }

    @Test
    void exportStreamsJsonWithAttachmentHeader() {
        when(exportService.export(userId)).thenReturn(sampleFile());
        ResponseEntity<byte[]> response = controller(objectMapper).exportBackupJson();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("backup_20261024.json");
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"version\":1");
    }

    @Test
    void exportSerializationFailureBecomesIllegalState() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(exportService.export(userId)).thenReturn(sampleFile());
        when(failing.writeValueAsBytes(any(BackupFile.class)))
                .thenThrow(new JsonProcessingException("tidak bisa") {});

        assertThatThrownBy(() -> controller(failing).exportBackupJson())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oversizedKnownContentLengthRejected() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(10L * 1024 * 1024 + 1);

        assertThatThrownBy(() -> controller(objectMapper).importData(request, "{\"version\":1}"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("PAYLOAD_TOO_LARGE");
                            assertThat(ex.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                        });
    }

    @Test
    void oversizedUnknownContentLengthRejectedViaBodyHeuristic() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        String hugeBody = "x".repeat(6 * 1024 * 1024);

        assertThatThrownBy(() -> controller(objectMapper).importData(request, hugeBody))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void brokenJsonRejectedBeforeImport() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(2L);

        assertThatThrownBy(() -> controller(objectMapper).importData(request, "{\""))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("MALFORMED_REQUEST"));
    }

    @Test
    void validJsonDelegatesToImportService() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(15L);
        when(importService.importFile(eq(userId), any(BackupFile.class)))
                .thenReturn(new ImportResult(1, 2, 3, 4));

        ImportResult result = controller(objectMapper).importData(request, "{\"version\": 1}");

        assertThat(result.categories()).isEqualTo(1);
        assertThat(result.transactions()).isEqualTo(2);
    }

    @Test
    void unknownLengthWithSmallBodyPassesHeuristic() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(importService.importFile(eq(userId), any(BackupFile.class)))
                .thenReturn(new ImportResult(0, 0, 0, 0));

        assertThat(controller(objectMapper).importData(request, "{\"version\": 1}")).isNotNull();
    }

    @Test
    void importServiceRejectsNullAndWrongVersion() {
        ImportService service = new ImportService(null);

        assertThatThrownBy(() -> service.importFile(userId, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("UNPROCESSABLE_ENTITY");
                            assertThat(ex.getMessage()).contains("(versi ?)");
                        });

        BackupFile v2 = new BackupFile(2, Instant.now(), null, null, null, null, null);
        assertThatThrownBy(() -> service.importFile(userId, v2))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("(versi 2)"));
    }

    @Test
    void backupRecordsArePlainDataHolders() {
        UUID id = UUID.randomUUID();
        var budget =
                new BackupFile.BackupBudget(
                        id, id, "daily", 1000L, "d", Instant.EPOCH, Instant.EPOCH);
        var closing =
                new BackupFile.BackupClosing(
                        id,
                        java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2026, 1, 31),
                        1L,
                        2L,
                        -1L,
                        3,
                        "f.xlsx",
                        Instant.EPOCH);
        assertThat(budget.id()).isEqualTo(id);
        assertThat(closing.fileName()).isEqualTo("f.xlsx");
        var profile = new BackupFile.BackupProfile("Nama", "Asia/Jakarta", 5L);
        assertThat(profile.displayName()).isEqualTo("Nama");
    }
}
