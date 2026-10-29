package com.fastwork.expense.transaction;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fastwork.expense.common.TxType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpsertTransactionRequest")
public record UpsertTransactionRequest(
        @NotNull LocalDate date,
        @NotNull TxType type,
        java.util.UUID categoryId,
        @Size(max = 200) String description,
        @NotNull @Min(1) @Max(999999999999L) Long amount) {}
