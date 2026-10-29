package com.fastwork.expense.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.MeResponse;
import com.fastwork.expense.user.PetPrincipal;
import com.fastwork.expense.user.PetUserDetailsService;
import com.fastwork.expense.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final PetUserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final LoginRateLimiter rateLimiter;

    // Marker khusus untuk memudahkan filtering di JSON log (Kibana/Grafana)
    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");
    private static final Marker LOGIN_SUCCESS_MARKER = MarkerFactory.getMarker("LOGIN_SUCCESS");
    private static final Marker LOGIN_FAILURE_MARKER = MarkerFactory.getMarker("LOGIN_FAILURE");

    @Transactional
    public LoginResponse login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String email = request.email() == null ? "" : request.email().trim();
        String ipAddress = resolveClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // 1. Log percobaan login (Audit awal)
        log.info(AUTH_MARKER, "Login attempt: email={}, ip={}, userAgent={}",
                maskEmail(email), ipAddress, userAgent);

        rateLimiter.assertAttemptAllowed(httpRequest.getRemoteAddr(), email);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            email, request.password()));
        } catch (DisabledException ex) {
            rateLimiter.recordFailure(email);
            log.warn(LOGIN_FAILURE_MARKER, "Login failed (Account Disabled): email={}, ip={}",
                    maskEmail(email), ipAddress);
            throw ApiException.unauthorized("Akun dinonaktifkan");
        } catch (AuthenticationException ex) {
            rateLimiter.recordFailure(email);
            // Jangan pernah log password! Log hanya alasan error dari Spring Security.
            log.warn(LOGIN_FAILURE_MARKER, "Login failed (Invalid Credentials): email={}, ip={}, reason={}",
                    maskEmail(email), ipAddress, ex.getMessage());
            throw ApiException.unauthorized("Email atau password salah");
        }

        // 2. Login Berhasil
        rateLimiter.recordSuccess(email);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        httpRequest.getSession(true);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        PetPrincipal principal = (PetPrincipal) authentication.getPrincipal();
        User user = userDetailsService.userById(principal.getId());

        // 3. INJEK userId ke MDC.
        // Berkat logback config Anda (uid=%X{userId:-}), semua log selanjutnya di
        // request ini otomatis ada userId-nya.
        MDC.put("userId", String.valueOf(user.getId()));

        String sessionId = httpRequest.getSession(false) != null ? httpRequest.getSession(false).getId() : "none";

        log.info(LOGIN_SUCCESS_MARKER, "Login successful: userId={}, email={}, ip={}, sessionId={}",
                user.getId(), maskEmail(email), ipAddress, sessionId);

        return new LoginResponse(MeResponse.from(user));
    }

    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication != null ? authentication.getName() : "unknown";

        log.info(AUTH_MARKER, "Logout executed: email={}, ip={}",
                maskEmail(email), resolveClientIp(httpRequest));

        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, authentication);
        SecurityContextHolder.clearContext();
        MDC.remove("userId"); // Bersihkan MDC setelah logout
    }

    // --- HELPER METHODS ---

    /**
     * Mengambil IP asli klien. Karena aplikasi berjalan di belakang Caddy/Docker,
     * httpRequest.getRemoteAddr() mungkin mengembalikan IP internal Docker.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isEmpty()) {
            return xfHeader.split(",")[0].trim(); // Ambil IP klien pertama
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * Masking email untuk keamanan PII (Personally Identifiable Information).
     * Contoh: "admin@fastwork.co" -> "a***@fastwork.co"
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@"))
            return "***";
        int atIndex = email.indexOf("@");
        if (atIndex <= 1)
            return "***" + email.substring(atIndex);
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}