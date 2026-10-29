package com.fastwork.expense.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Korelasi request: menerima header {@code X-Request-Id} hanya bila valid (1-64 karakter
 * alfanumerik, underscore, minus); selain itu generator ID baru (UUID hex). ID selalu dikembalikan
 * di header respons dan dibersihkan dari MDC di {@code finally} (tidak menghapus key milik
 * integrasi lain).
 */
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final Pattern VALID_INBOUND = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId =
                inbound != null && VALID_INBOUND.matcher(inbound).matches()
                        ? inbound
                        : UUID.randomUUID().toString().replace("-", "");
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
