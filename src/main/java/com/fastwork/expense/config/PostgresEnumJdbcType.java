package com.fastwork.expense.config;

/**
 * Tipe JDBC untuk kolom enum PostgreSQL.
 *
 * <p>Label enum pada skema memakai huruf kecil ({@code owner}, {@code income}, {@code daily}),
 * sedangkan enum Java memakai huruf besar. Label hasil konverter dikirim sebagai parameter bertipe
 * {@code unknown} sehingga PostgreSQL memetakannya ke tipe kolom enum.
 */
public class PostgresEnumJdbcType extends PostgresOpaqueTextJdbcType {}
