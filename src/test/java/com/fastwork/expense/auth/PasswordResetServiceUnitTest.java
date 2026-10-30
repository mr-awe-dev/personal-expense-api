package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

/** Unit test PasswordResetService untuk jalur yang tidak bisa dijangkau lewat HTTP. */
class PasswordResetServiceUnitTest {

    private PasswordResetService service(UserRepository userRepository) {
        return new PasswordResetService(
                mock(PasswordResetRepository.class),
                userRepository,
                mock(UserService.class),
                mock(PasswordEncoder.class),
                mock(LoginRateLimiter.class),
                mock(JdbcClient.class));
    }

    @Test
    void issueUnknownEmailReturnsNotFound() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(userRepository).issue("hilang@contoh.com"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("NOT_FOUND");
                            assertThat(ex.getMessage()).contains("hilang@contoh.com");
                        });
    }

    @Test
    void normalizeCodeHandlesNullTrimAndUppercase() {
        assertThat(PasswordResetService.normalizeCode(null)).isEmpty();
        assertThat(PasswordResetService.normalizeCode("  ab-cd2 ")).isEqualTo("AB-CD2");
    }

    @Test
    void sha256HexMatchesKnownVector() {
        assertThat(PasswordResetService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void codeTtlIsThirtyMinutes() {
        assertThat(PasswordResetService.CODE_TTL.toMinutes()).isEqualTo(30);
    }
}
