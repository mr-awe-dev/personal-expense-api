package com.fastwork.expense.email;

import com.fastwork.expense.common.ApiException;

/**
 * Implementasi bawaan saat fitur email dimatikan. Selalu menolak dengan 503 eksplisit agar
 * pengguna tahu fiturnya tidak hidup — bukan 202 generik yang membekukan bingkai alur reset.
 */
public class NoopEmailSender implements EmailSender {

    @Override
    public void send(EmailMessage message) {
        throw ApiException.serviceUnavailable("Fitur email belum diaktifkan pada lingkungan ini");
    }
}
