package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.SecurityContextRepository;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.PetUserDetailsService;
import com.fastwork.expense.user.UserRepository;

/** Unit test AuthService: cabang akun dinonaktifkan dan email null tanpa HTTP layer. */
class AuthUnitTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final PetUserDetailsService userDetailsService = mock(PetUserDetailsService.class);
    private final SecurityContextRepository securityContextRepository =
            mock(SecurityContextRepository.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final AuthService service =
            new AuthService(authenticationManager, userDetailsService, securityContextRepository, rateLimiter);

    @Test
    void disabledAccountReportsDedicatedMessage() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("akun nonaktif"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.getMessage()).isEqualTo("Akun dinonaktifkan");
                            assertThat(ex.code()).isEqualTo("UNAUTHORIZED");
                        });
        verify(rateLimiter).recordFailure("a@b.c");
    }

    @Test
    void nullEmailIsNormalizedToEmptyBeforeRateLimiter() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("x"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(null, "password"),
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex ->
                                assertThat(ex.getMessage())
                                        .isEqualTo("Email atau password salah"));
        verify(rateLimiter).assertAttemptAllowed(any(), org.mockito.ArgumentMatchers.eq(""));
        verify(rateLimiter).recordFailure("");
    }

    @Test
    void passwordResetEntityExposesEveryField() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant expiry = Instant.ofEpochSecond(1_000);
        PasswordReset reset = new PasswordReset(id, userId, "hash-abc", expiry);

        assertThat(reset.getId()).isEqualTo(id);
        assertThat(reset.getUserId()).isEqualTo(userId);
        assertThat(reset.getTokenHash()).isEqualTo("hash-abc");
        assertThat(reset.getExpiresAt()).isEqualTo(expiry);
        assertThat(reset.getUsedAt()).isNull();
        assertThat(reset.getCreatedAt()).isNull();

        reset.markUsed();
        assertThat(reset.getUsedAt()).isNotNull();
    }
}
