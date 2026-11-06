package com.fastwork.expense.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Format atribut audit untuk log: masking email (PII) dan resolusi IP klien di balik proxy
 * (Caddy/Docker memantulkan `X-Forwarded-For`/`X-Real-IP`). Dipakai bersama oleh log login,
 * reset password, dan rate limiter.
 */
public final class AuditLog {

    private AuditLog() {}

    /**
     * Masking email agar log tidak menyimpan PII utuh; "admin@fastwork.co" ->
     * "a***@fastwork.co". Input tanpa "@" dianggap bukan email dan seluruhnya ditutup.
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf("@");
        if (atIndex <= 1) {
            return "***" + email.substring(atIndex);
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    /**
     * IP asli klien: hop pertama dari {@code X-Forwarded-For}, lalu {@code X-Real-IP}, terakhir
     * alamat remote langsung (IP internal Docker saat di belakang reverse proxy).
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
