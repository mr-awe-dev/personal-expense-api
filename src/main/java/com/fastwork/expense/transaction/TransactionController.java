package com.fastwork.expense.transaction;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fastwork.expense.common.TxType;
import com.fastwork.expense.user.CurrentUserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Transaksi pemasukan & pengeluaran berjalan")
public class TransactionController {

    private final CurrentUserService currentUser;
    private final TransactionService transactionService;

    @GetMapping
    @Operation(
            summary = "Daftar transaksi berjalan (keyset pagination) + total sesuai filter",
            description =
                    "Cursor dan sort dipakai bersama: cursor berisi posisi terakhir sesuai sort terpilih.")
    public TransactionPage list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) TxType type,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "DATE_DESC") TransactionSort sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") @Min(1) @Max(200) Integer limit) {
        UUID userId = currentUser.principal().getId();
        TransactionService.Filter filter =
                new TransactionService.Filter(from, to, type, categoryId, q);
        return transactionService.page(userId, filter, sort, cursor, limit);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Upsert idempotent (ID dibuat klien untuk retry/undo tanpa duplikat)",
            description = "Transaksi milik user lain tidak pernah tersentuh; ID asing → 404.")
    public TransactionResponse upsert(
            @PathVariable UUID id, @Valid @RequestBody UpsertTransactionRequest request) {
        return transactionService.upsert(currentUser.principal().getId(), id, request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Ubah sebagian field transaksi")
    public TransactionResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateTransactionRequest request) {
        return transactionService.update(currentUser.principal().getId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus transaksi (Undo = PUT ulang dengan ID & data yang sama)")
    public void delete(@PathVariable UUID id) {
        transactionService.delete(currentUser.principal().getId(), id);
    }
}
