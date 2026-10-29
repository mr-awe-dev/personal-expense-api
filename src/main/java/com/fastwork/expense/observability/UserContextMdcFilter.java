package com.fastwork.expense.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fastwork.expense.user.PetPrincipal;

/**
 * Mengisi MDC {@code userId} HANYA dari SecurityContext (setelah Spring Security Chain memulihkan
 * autentikasi) — tidak pernah dari header input klien. Dibersihkan di {@code finally} agar tidak
 * bocor antar request (thread virtual per request).
 */
public class UserContextMdcFilter extends OncePerRequestFilter {

    public static final String MDC_USER_ID = "userId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userId = null;
        if (authentication != null
                && authentication.getPrincipal() instanceof PetPrincipal principal
                && principal.getId() != null) {
            userId = principal.getId().toString();
        }
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_ID);
        }
    }
}
