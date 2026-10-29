package com.fastwork.expense.config;

/**
 * Tipe JDBC untuk kolom PostgreSQL {@code citext}.
 *
 * <p>Perilaku runtime tetap sama dengan {@code varchar}: nilai dibaca dan ditulis sebagai teks.
 * Bedanya hanya cara parameter dikirim, sehingga perbandingan tetap case-insensitive.
 */
public class CitextJdbcType extends PostgresOpaqueTextJdbcType {}
