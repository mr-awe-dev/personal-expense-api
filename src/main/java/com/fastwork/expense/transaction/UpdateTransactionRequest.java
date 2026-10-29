package com.fastwork.expense.transaction;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fastwork.expense.common.TxType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpdateTransactionRequest")
public record UpdateTransactionRequest(
        LocalDate date,
        TxType type,
        java.util.UUID categoryId,
        @Size(max = 200) String description,
        @Min(1) @Max(999999999999L) Long amount) {}
