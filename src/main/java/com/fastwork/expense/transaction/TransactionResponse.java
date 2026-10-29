package com.fastwork.expense.transaction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fastwork.expense.common.TxType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Transaction")
public record TransactionResponse(
        UUID id,
        LocalDate date,
        TxType type,
        UUID categoryId,
        String categoryName,
        String description,
        long amount,
        Instant createdAt,
        Instant updatedAt) {}
