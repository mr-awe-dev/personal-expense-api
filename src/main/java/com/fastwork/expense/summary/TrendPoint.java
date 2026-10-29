package com.fastwork.expense.summary;

import java.time.LocalDate;

/** Agregat harian untuk tren pengeluaran (SQL GROUP BY, celah tanggal diisi 0). */
public record TrendPoint(LocalDate date, long expense) {}
