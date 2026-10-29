package com.fastwork.expense.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.UuidV7;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * Pemulihan password tanpa email server (spesifikasi v5: reset via CLI). Kode acak satu-kali
 * diterbitkan oleh perintah CLI {@code issue-reset-code}, disimpan sebagai SHA-256, lalu ditukar di
 * halaman reset melalui {@code POST /api/v1/auth/reset-password}. Sukses reset menghapus seluruh
 * sesi aktif pengguna.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    public static final Duration CODE_TTL = Duration.ofMinutes(30);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordResetRepository resetRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final JdbcClient jdbcClient;

    @Transactional
    public String issue(String email) {
        User user =
                userRepository
                        .findByEmail(UserService.normalizeEmail(email))
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "Pengguna tidak ditemukan: "
                                                        + UserService.normalizeEmail(email)));
        resetRepository.deleteByUserId(user.getId());
        byte[] random = new byte[10];
        SECURE_RANDOM.nextBytes(random);
        String code = HexFormat.of().withUpperCase().formatHex(random);
        resetRepository.save(
                new PasswordReset(
                        UuidV7.random(),
                        user.getId(),
                        sha256Hex(code),
                        Instant.now().plus(CODE_TTL)));
        return code;
    }

    @Transactional
    public void consume(ResetPasswordRequest request, String clientIp) {
        String email = UserService.normalizeEmail(request.email());
        rateLimiter.assertAttemptAllowed(clientIp, email, "reset");
        User user = userRepository.findByEmail(email).orElse(null);
        PasswordReset token =
                user == null
                        ? null
                        : resetRepository
                                .findByUserIdAndTokenHashAndUsedAtIsNullAndExpiresAtAfter(
                                        user.getId(),
                                        sha256Hex(normalizeCode(request.code())),
                                        Instant.now())
                                .orElse(null);
        if (token == null) {
            rateLimiter.recordFailure(email, "reset");
            throw ApiException.badRequest(
                    "INVALID_RESET_CODE", "Kode pemulihan tidak valid atau sudah kedaluwarsa");
        }
        UserService.validatePassword(request.newPassword());
        token.markUsed();
        resetRepository.save(token);
        resetRepository.deleteByUserIdAndIdNot(user.getId(), token.getId());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        invalidateSessions(email);
        rateLimiter.recordSuccess(email, "reset");
    }

    private void invalidateSessions(String email) {
        jdbcClient
                .sql("delete from SPRING_SESSION where PRINCIPAL_NAME = :principal")
                .param("principal", email)
                .update();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
