package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.PetPrincipal;
import com.fastwork.expense.user.PetUserDetailsService;
import com.fastwork.expense.user.User;

/** Unit test AuthService: cabang akun dinonaktifkan dan email null tanpa HTTP layer. */
class AuthUnitTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final PetUserDetailsService userDetailsService = mock(PetUserDetailsService.class);
    private final SecurityContextRepository securityContextRepository =
            mock(SecurityContextRepository.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final AuthService service =
            new AuthService(
                    authenticationManager,
                    userDetailsService,
                    securityContextRepository,
                    rateLimiter);

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
                .thenThrow(
                        new org.springframework.security.authentication.BadCredentialsException(
                                "x"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(null, "password"),
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("Email atau password salah"));
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

    @Test
    void forwardedForUsesFirstClientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", " 203.0.113.10, 10.0.0.1");
        request.addHeader("User-Agent", "JUnit");

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        request,
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);

        verify(rateLimiter).assertAttemptAllowed(request.getRemoteAddr(), "a@b.c");
    }

    @Test
    void realIpHeaderIsAcceptedWhenForwardedForIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "203.0.113.20");

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        request,
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);

        verify(rateLimiter).assertAttemptAllowed(request.getRemoteAddr(), "a@b.c");
    }

    @Test
    void emptyForwardedForFallsBackToRealIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "");
        request.addHeader("X-Real-IP", "203.0.113.30");

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        request,
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void loginFailureHandlesEmailWithoutAtSymbol() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("invalid-email", "password"),
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);

        verify(rateLimiter).recordFailure("invalid-email");
    }

    @Test
    void loginFailureHandlesEmailStartingWithAtSymbol() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("@example.com", "password"),
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);

        verify(rateLimiter).recordFailure("@example.com");
    }

    @Test
    void loginFailureHandlesEmailWithAtSymbolAtIndexOne() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@example.com", "password"),
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void loginSuccessSavesSecurityContextAndRecordsSuccess() {
        UUID userId = UUID.randomUUID();

        PetPrincipal principal = mock(PetPrincipal.class);
        when(principal.getId()).thenReturn(userId);

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authentication);
        when(userDetailsService.userById(userId)).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        LoginResponse result =
                service.login(new LoginRequest("a@b.c", "password"), request, response);

        assertThat(result).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        assertThat(MDC.get("userId")).isEqualTo(userId.toString());

        verify(rateLimiter).recordSuccess("a@b.c");
        verify(securityContextRepository)
                .saveContext(any(SecurityContext.class), eq(request), eq(response));
        verify(userDetailsService).userById(userId);
    }

    @Test
    void loginSuccessWithForwardedIpAndExistingSession() {
        UUID userId = UUID.randomUUID();

        PetPrincipal principal = mock(PetPrincipal.class);
        when(principal.getId()).thenReturn(userId);

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authentication);
        when(userDetailsService.userById(userId)).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.50, 10.0.0.1");
        request.getSession(true);

        MockHttpServletResponse response = new MockHttpServletResponse();

        LoginResponse result =
                service.login(new LoginRequest("admin@example.com", "password"), request, response);

        assertThat(result).isNotNull();
        assertThat(request.getSession(false)).isNotNull();
        assertThat(MDC.get("userId")).isEqualTo(userId.toString());
    }

    @Test
    void loginSuccessWithFreshRequestLogsPlaceholderSessionId() {
        UUID userId = UUID.randomUUID();

        PetPrincipal principal = mock(PetPrincipal.class);
        when(principal.getId()).thenReturn(userId);

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authentication);
        when(userDetailsService.userById(userId)).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        LoginResponse result =
                service.login(new LoginRequest("fresh@example.com", "password"), request, response);

        assertThat(result).isNotNull();
        // Baris getRequest.getSession(true) membuat session pada login sukses.
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getId()).isNotBlank();
        assertThat(MDC.get("userId")).isEqualTo(userId.toString());
    }

    @Test
    void logoutWithAuthenticatedUserClearsSecurityContextAndMdc() {
        Authentication authentication = mock(Authentication.class);

        when(authentication.getName()).thenReturn("admin@example.com");

        SecurityContext context = SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        MDC.put("userId", "123");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.logout(request, response);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void logoutWithoutAuthenticationClearsContextAndMdc() {
        SecurityContextHolder.clearContext();
        MDC.put("userId", "123");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.logout(request, response);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void loginFailureLogsFirstForwardedIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        request,
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void loginUsesRemoteAddressWhenForwardedHeadersAreAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        request,
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void loginTrimsEmailBeforeAuthentication() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("  admin@example.com  ", "password"),
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);

        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);

        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getPrincipal()).isEqualTo("admin@example.com");

        verify(rateLimiter).recordFailure("admin@example.com");
    }

    @Test
    void loginUsesRealIpWhenForwardedForIsEmpty() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.addHeader("X-Forwarded-For", "");
        request.addHeader("X-Real-IP", "203.0.113.30");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        request,
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void emptyRealIpHeaderFallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Forwarded-For", "");
        request.addHeader("X-Real-IP", "");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", "password"),
                                        request,
                                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class);

        verify(rateLimiter).assertAttemptAllowed("192.168.1.10", "a@b.c");
    }
}
