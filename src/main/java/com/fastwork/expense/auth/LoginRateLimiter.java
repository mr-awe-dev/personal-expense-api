package com.fastwork.expense.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.fastwork.expense.common.ApiException;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;

/**
 * Rate limiter percobaan login: batas per-IP per menit, batas per-email per jendela 15 menit, dan
 * delay progresif per-email setelah kegagalan (reset saat sukses). In-memory, per instance.
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class LoginRateLimiter {

    private static final Duration EMAIL_WINDOW = Duration.ofMinutes(15);

    private boolean enabled = true;
    private int ipRequestsPerMinute = 20;
    private int emailRequestsPerWindow = 5;
    private long delayBaseMs = 500;
    private long delayMaxMs = 8000;

    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, EmailState> emailStates = new ConcurrentHashMap<>();

    private static final class EmailState {
        volatile Bucket attempts;
        volatile Instant nextAllowedAt = Instant.EPOCH;
        volatile int consecutiveFailures;
    }

    public void assertAttemptAllowed(String clientIp, String email) {
        assertAttemptAllowed(clientIp, email, "login");
    }

    public void assertAttemptAllowed(String clientIp, String email, String action) {
        if (!enabled) {
            return;
        }
        Bucket ipBucket =
                ipBuckets.computeIfAbsent(
                        clientIp,
                        key -> fixedWindowBucket(ipRequestsPerMinute, Duration.ofMinutes(1)));
        if (!ipBucket.tryConsume(1)) {
            throw ApiException.rateLimited(
                    60, "Terlalu banyak percobaan dari jaringan ini. Coba lagi nanti.");
        }
        if (email == null || email.isBlank()) {
            return;
        }
        String emailKey = email.trim().toLowerCase(Locale.ROOT) + '#' + action;
        EmailState state = emailStates.computeIfAbsent(emailKey, key -> new EmailState());
        synchronized (state) {
            if (state.attempts == null) {
                state.attempts = fixedWindowBucket(emailRequestsPerWindow, EMAIL_WINDOW);
            }
        }
        if (!state.attempts.tryConsume(1)) {
            EstimationProbe probe = state.attempts.estimateAbilityToConsume(1);
            long seconds =
                    Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
            throw ApiException.rateLimited(
                    seconds,
                    "Terlalu banyak percobaan " + action + " untuk email ini. Coba lagi nanti.");
        }
        Instant now = Instant.now();
        Instant allowed = state.nextAllowedAt;
        if (now.isBefore(allowed)) {
            long seconds = Math.max(1, Duration.between(now, allowed).toSeconds());
            throw ApiException.rateLimited(seconds, "Tunggu beberapa detik sebelum mencoba lagi.");
        }
    }

    public void recordFailure(String email) {
        recordFailure(email, "login");
    }

    public void recordFailure(String email, String action) {
        if (!enabled || email == null || email.isBlank()) {
            return;
        }
        EmailState state =
                emailStates.computeIfAbsent(
                        email.trim().toLowerCase(Locale.ROOT) + '#' + action,
                        key -> new EmailState());
        synchronized (state) {
            state.consecutiveFailures = Math.min(state.consecutiveFailures + 1, 10);
            long delayMs = delayFor(state.consecutiveFailures);
            state.nextAllowedAt = delayMs <= 0 ? Instant.EPOCH : Instant.now().plusMillis(delayMs);
        }
    }

    public void recordSuccess(String email) {
        recordSuccess(email, "login");
    }

    public void recordSuccess(String email, String action) {
        if (!enabled || email == null || email.isBlank()) {
            return;
        }
        EmailState state =
                emailStates.computeIfAbsent(
                        email.trim().toLowerCase(Locale.ROOT) + '#' + action,
                        key -> new EmailState());
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.nextAllowedAt = Instant.EPOCH;
        }
    }

    public String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private long delayFor(int consecutiveFailures) {
        long base = Math.max(0, delayBaseMs);
        long max = Math.max(base, delayMaxMs);
        if (base <= 0) {
            return 0;
        }
        long scaled = base * (1L << Math.min(Math.max(consecutiveFailures - 1, 0), 20));
        return Math.min(scaled, max);
    }

    private static Bucket fixedWindowBucket(int capacity, Duration window) {
        Bandwidth limit =
                Bandwidth.builder().capacity(capacity).refillIntervally(capacity, window).build();
        return Bucket.builder().addLimit(limit).build();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setIpRequestsPerMinute(int ipRequestsPerMinute) {
        this.ipRequestsPerMinute = ipRequestsPerMinute;
    }

    public void setEmailRequestsPerWindow(int emailRequestsPerWindow) {
        this.emailRequestsPerWindow = emailRequestsPerWindow;
    }

    public void setDelayBaseMs(long delayBaseMs) {
        this.delayBaseMs = delayBaseMs;
    }

    public void setDelayMaxMs(long delayMaxMs) {
        this.delayMaxMs = delayMaxMs;
    }
}
