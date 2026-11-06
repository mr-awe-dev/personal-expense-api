package com.fastwork.expense.email;

/**
 * Surat teks sederhana yang siap dikirim. Kode OTP TIDAK boleh masuk ke log — pemanggil yang
 * bertanggung jawab memastikannya (lihat {@code PasswordResetService}).
 */
public record EmailMessage(String to, String subject, String text) {}
