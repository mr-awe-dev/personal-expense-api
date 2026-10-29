package com.fastwork.expense.transaction;

/** Urutan daftar transaksi; semua memakai keyset pagination. */
public enum TransactionSort {
    DATE_DESC,
    DATE_ASC,
    AMOUNT_DESC,
    AMOUNT_ASC,
    CATEGORY_ASC,
    CATEGORY_DESC
}
