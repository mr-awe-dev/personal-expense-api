package com.fastwork.expense.summary;

import java.time.LocalDate;
import java.util.UUID;

/** Satu baris tabel summary. */
public record TableRow(
        UUID id,
        LocalDate date,
        String type,
        UUID categoryId,
        String categoryName,
        String description,
        long amount) {}
