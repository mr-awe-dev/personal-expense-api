package com.fastwork.expense.summary;

import java.util.UUID;

/**
 * Satu baris agregasi per kategori/sumber. `share` = persentase pembulatan *largest remainder*
 * sehingga total 100% (0 saat tidak ada data).
 */
public record CategoryShare(
        UUID categoryId, String categoryName, boolean categoryArchived, long amount, long share) {}
