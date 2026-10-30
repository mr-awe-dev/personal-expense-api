package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.fastwork.expense.common.ApiException;

/** Rate limiter murni unit: bucket IP, jendela email, delay progresif, dan util. */
class LoginRateLimiterUnitTest {

    private LoginRateLimiter limiter(int ipPerMinute, int emailPerWindow, long baseMs, long maxMs) {
        LoginRateLimiter limiter = new LoginRateLimiter();
        limiter.setEnabled(true);
        limiter.setIpRequestsPerMinute(ipPerMinute);
        limiter.setEmailRequestsPerWindow(emailPerWindow);
        limiter.setDelayBaseMs(baseMs);
        limiter.setDelayMaxMs(maxMs);
        return limiter;
    }

    @Test
    void disabledLimiterAllowsEverything() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        limiter.setEnabled(false);
        for (int i = 0; i < 100; i++) {
            limiter.assertAttemptAllowed("1.1.1.1", "a@b.c");
        }
        limiter.recordFailure("a@b.c");
        limiter.assertAttemptAllowed("1.1.1.1", "a@b.c");
    }

    @Test
    void ipBucketBlocksAfterCapacity() {
        LoginRateLimiter limiter = limiter(2, 100, 500, 8000);
        limiter.assertAttemptAllowed("2.2.2.2", "satu@b.c");
        limiter.assertAttemptAllowed("2.2.2.2", "dua@b.c");
        assertThatThrownBy(() -> limiter.assertAttemptAllowed("2.2.2.2", "tiga@b.c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("RATE_LIMITED");
                            assertThat(ex.retryAfterSeconds()).isEqualTo(60L);
                        });
    }

    @Test
    void blankEmailShortCircuitsAfterIpBucket() {
        LoginRateLimiter limiter = limiter(10, 1, 500, 8000);
        assertThatCode(() -> limiter.assertAttemptAllowed("3.3.3.3", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.assertAttemptAllowed("3.3.3.3", "  "))
                .doesNotThrowAnyException();
    }

    @Test
    void emailWindowBlocksAfterCapacity() {
        LoginRateLimiter limiter = limiter(1000, 2, 0, 0);
        limiter.assertAttemptAllowed("4.4.4.4", "jeda@b.c");
        limiter.assertAttemptAllowed("4.4.4.4", "jeda@b.c");
        assertThatThrownBy(() -> limiter.assertAttemptAllowed("4.4.4.4", "jeda@b.c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("RATE_LIMITED");
                            assertThat(ex.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
                        });
    }

    @Test
    void progressiveDelayGatesNextAttemptUntilWindow() {
        LoginRateLimiter limiter = limiter(1000, 100, 5000, 8000);
        limiter.recordFailure("lambat@b.c");
        assertThatThrownBy(() -> limiter.assertAttemptAllowed("5.5.5.5", "lambat@b.c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex ->
                                assertThat(ex.getMessage())
                                        .isEqualTo("Tunggu beberapa detik sebelum mencoba lagi."));

        limiter.recordSuccess("lambat@b.c");
        assertThatCode(() -> limiter.assertAttemptAllowed("5.5.5.5", "lambat@b.c"))
                .doesNotThrowAnyException();
    }

    @Test
    void zeroBaseDelayKeepsAttemptImmediate() {
        LoginRateLimiter limiter = limiter(1000, 100, 0, 8000);
        limiter.recordFailure("nol@b.c");
        assertThatCode(() -> limiter.assertAttemptAllowed("6.6.6.6", "nol@b.c"))
                .doesNotThrowAnyException();
    }

    @Test
    void manyFailuresCapExponentAndDelayMax() {
        LoginRateLimiter limiter = limiter(1000, 1000, 100, 150);
        for (int i = 0; i < 30; i++) {
            limiter.recordFailure("banjir@b.c");
        }
        assertThatThrownBy(() -> limiter.assertAttemptAllowed("7.7.7.7", "banjir@b.c"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void recordAndSuccessIgnoreBlankOrNullEmails() {
        LoginRateLimiter limiter = limiter(10, 10, 500, 8000);
        limiter.recordFailure(null);
        limiter.recordFailure("  ");
        limiter.recordSuccess(null);
        limiter.recordSuccess("  ");
        // kunci baru HANYA lewat recordSuccess → computeIfAbsent membuat EmailState
        limiter.recordSuccess("hanya-sukses@b.c");
        limiter.recordFailure("aksi@b.c", "reset");
        limiter.recordSuccess("aksi@b.c", "reset");
        assertThat(limiter.normalizeEmail("  A@B.com ")).isEqualTo("a@b.com");
        assertThat(limiter.normalizeEmail(null)).isEmpty();
    }

    @Test
    void stateKeyIsCaseInsensitivePerAction() {
        LoginRateLimiter limiter = limiter(1000, 100, 4000, 8000);
        limiter.recordFailure("Campur@Contoh.COM");
        assertThatThrownBy(
                        () ->
                                limiter.assertAttemptAllowed(
                                        "8.8.8.8", "campur@contoh.com".toUpperCase(Locale.ROOT)))
                .isInstanceOf(ApiException.class);
    }
}
