package com.fastwork.expense.transaction;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TransactionPage")
public record TransactionPage(
        List<TransactionResponse> items, String nextCursor, TransactionTotals totals) {}
