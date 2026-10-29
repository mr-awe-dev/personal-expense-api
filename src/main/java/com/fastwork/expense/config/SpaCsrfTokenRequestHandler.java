package com.fastwork.expense.config;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Menyesuaikan penanganan CSRF untuk SPA.
 *
 * <p>Klien membaca cookie {@code XSRF-TOKEN} lalu mengirimkannya kembali di header {@code
 * X-XSRF-TOKEN}. Handler bawaan Spring Security hanya mencari token pada parameter {@code _csrf},
 * sedangkan handler {@code Xor} mengarang token dengan XOR sehingga token mentah dari cookie tidak
 * cocok. Handler ini menerima token mentah dari header apa adanya.
 */
public class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken) {
        super.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String header = request.getHeader(csrfToken.getHeaderName());
        if (header != null && !header.isBlank()) {
            return header;
        }
        return super.resolveCsrfTokenValue(request, csrfToken);
    }
}
