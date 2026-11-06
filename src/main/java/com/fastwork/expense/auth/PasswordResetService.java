package com.fastwork.expense.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.AuditLog;
import com.fastwork.expense.common.UuidV7;
import com.fastwork.expense.email.EmailMessage;
import com.fastwork.expense.email.EmailSender;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pemulihan password lewat OTP email: kode 8 digit diterbitkan oleh permintaan pengguna
 * ({@code POST /api/v1/auth/forgot-password}) dan dikirim ke email yang terdaftar, disimpan
 * sebagai SHA-256, lalu ditukar di halaman reset melalui {@code POST /api/v1/auth/reset-password}.
 * Sukses reset menghapus seluruh sesi aktif pengguna. Perintah CLI {@code issue-reset-code}
 * tetap dipertahankan sebagai jalur darurat bila kirim email tidak tersedia.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    public static final Duration CODE_TTL = Duration.ofMinutes(30);

    /**
     * OTP 8 digit numerik: rentang 10.000.000–99.999.999 (bukan modulo) supaya tiap digit
     * seragam terdistribusi dari SecureRandom.
     */
    static final int OTP_MIN_INCLUSIVE = 10_000_000;

    static final int OTP_SPAN = 90_000_000;

    private static final String SUBJECT = "Kode pemulihan password — Personal Expense";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Marker log khusus auth, konsisten dengan {@link AuthService} untuk filter di JSON log. */
    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");

    private final PasswordResetRepository resetRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final JdbcClient jdbcClient;
    private final EmailSender emailSender;

    /**
     * Alur OTP pengguna: terbitkan kode baru lalu kirim ke email terdaftar. Email yang tidak
     * terdaftar TIDAK diberi perbedaan jawaban (anti enumerasi) — hanya dicatat ber-mask.
     */
    @Transactional
    public void request(String email, String clientIp) {
        String normalized = UserService.normalizeEmail(email);
        rateLimiter.assertAttemptAllowed(clientIp, normalized, "reset");
        User user = userRepository.findByEmail(normalized).orElse(null);
        if (user == null) {
            log.info(
                    AUTH_MARKER,
                    "Reset requested (email tak terdaftar): email={}, ip={}",
                    AuditLog.maskEmail(normalized),
                    clientIp);
            return;
        }
        String code = issueForUser(user);
        emailSender.send(new EmailMessage(user.getEmail(), SUBJECT, recoveryText(code)));
        log.info(
                AUTH_MARKER,
                "Reset requested: userId={}, email={}, ip={}",
                user.getId(),
                AuditLog.maskEmail(user.getEmail()),
                clientIp);
    }

    /** Jalur darurat CLI: melempar 404 bila email tidak terdaftar. */
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
        return issueForUser(user);
    }

    /** Terbitkan kode baru untuk pengguna yang sudah pasti ada; menghapus kode lama lebih dahulu. */
    private String issueForUser(User user) {
        resetRepository.deleteByUserId(user.getId());
        String code = String.valueOf(SECURE_RANDOM.nextInt(OTP_SPAN) + OTP_MIN_INCLUSIVE);
        resetRepository.save(
                new PasswordReset(
                        UuidV7.random(),
                        user.getId(),
                        sha256Hex(code),
                        Instant.now().plus(CODE_TTL)));
        log.info(
                AUTH_MARKER,
                "Reset code issued: userId={}, email={}, ttlMinutes={}",
                user.getId(),
                AuditLog.maskEmail(user.getEmail()),
                CODE_TTL.toMinutes());
        return code;
    }

    private String recoveryText(String code) {
        return "Kode pemulihan password Anda:\n"
                + "\n"
                + code
                + "\n"
                + "\n"
                + "Kode berlaku "
                + CODE_TTL.toMinutes()
                + " menit dan hanya berlaku sekali.\n"
                + "Jika Anda tidak meminta kode ini, abaikan email ini — password Anda tidak berubah.";
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
            log.warn(
                    AUTH_MARKER,
                    "Reset rejected (invalid code): email={}, ip={}",
                    AuditLog.maskEmail(email),
                    clientIp);
            throw ApiException.badRequest(
                    "INVALID_RESET_CODE", "Kode pemulihan tidak valid atau sudah kedaluwarsa");
        }
        UserService.validatePassword(request.newPassword());
        token.markUsed();
        resetRepository.save(token);
        resetRepository.deleteByUserIdAndIdNot(user.getId(), token.getId());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        int sessions = invalidateSessions(email);
        rateLimiter.recordSuccess(email, "reset");
        log.info(
                AUTH_MARKER,
                "Reset consumed: userId={}, email={}, ip={}, sessionsInvalidated={}",
                user.getId(),
                AuditLog.maskEmail(email),
                clientIp,
                sessions);
    }

    private int invalidateSessions(String email) {
        return jdbcClient
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
