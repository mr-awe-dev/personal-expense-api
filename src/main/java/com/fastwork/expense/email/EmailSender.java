package com.fastwork.expense.email;

/** Abstraksi pengiriman email tanpa tautan konkret pada provider tertentu. */
public interface EmailSender {

    void send(EmailMessage message);
}
