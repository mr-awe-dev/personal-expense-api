package com.fastwork.expense.receipt;

/** Kegagalan OCR (biner tidak ada, timeout, exit non-nol, teks kosong). */
public class ReceiptOcrException extends RuntimeException {

    public ReceiptOcrException(String message) {
        super(message);
    }

    public ReceiptOcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
