package com.fastwork.expense.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.context.SecurityContextRepository;

import com.fastwork.expense.auth.LoginRateLimiter;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserRole;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialType;
import com.yubico.webauthn.data.UserIdentity;

/** Unit test alur passkey: guard fitur mati, validasi sesi, dan pemetaan daftar. */
@ExtendWith(MockitoExtension.class)
class PasskeyServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("01a191c7-0000-7000-8000-000000000000");
    private static final User USER =
            new User(USER_ID, "pemilik@example.com", "hash", UserRole.OWNER);

    @Mock private PasskeyRepository passkeys;
    @Mock private UserRepository users;
    @Mock private SecurityContextRepository securityContextRepository;
    @Mock private LoginRateLimiter rateLimiter;

    private WebAuthnProperties properties;
    private PasskeyService service;

    @BeforeEach
    void setUp() {
        properties = new WebAuthnProperties();
        properties.setEnabled(true);
        properties.setRpId("localhost");
        properties.setRpName("Personal Expense");
        properties.setAllowedOrigins(List.of("http://localhost:5173"));

        PasskeyCredentialRepository credentials = new PasskeyCredentialRepository(passkeys, users);
        service =
                new PasskeyService(
                        new WebAuthnServer(properties, credentials),
                        properties,
                        passkeys,
                        users,
                        securityContextRepository,
                        rateLimiter);
    }

    private static void assertApiException(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            int status,
            String code) {
        assertThatThrownBy(action)
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> {
                            ApiException api = (ApiException) error;
                            assertThat(api.status().value()).isEqualTo(status);
                            assertThat(api.code()).isEqualTo(code);
                        });
    }

    @Test
    void everyEntryPointRejectsDisabledFeature() {
        properties.setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertApiException(() -> service.createRegistrationOptions(USER, request), 404, "NOT_FOUND");
        assertApiException(() -> service.completeRegistration(USER, "{}", request), 404, "NOT_FOUND");
        assertApiException(() -> service.createLoginOptions(request), 404, "NOT_FOUND");
        assertApiException(() -> service.login("{}", request, response), 404, "NOT_FOUND");
        assertApiException(() -> service.list(USER_ID), 404, "NOT_FOUND");
        assertApiException(() -> service.delete(USER_ID, UUID.randomUUID()), 404, "NOT_FOUND");
        verifyNoInteractions(passkeys, rateLimiter);
    }

    @Test
    void createRegistrationOptionsStoresOptionsInSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        String json = service.createRegistrationOptions(USER, request);

        assertThat(json).contains("\"challenge\"");
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("PASSKEY_REGISTRATION_OPTIONS"))
                .isNotNull();
    }

    @Test
    void createRegistrationOptionsFallsBackToEmailForBlankDisplayName() throws Exception {
        USER.setDisplayName("   ");
        assertThat(service.createRegistrationOptions(USER, new MockHttpServletRequest()))
                .contains("\"challenge\"");

        USER.setDisplayName("Kepala Keluarga");
        assertThat(service.createRegistrationOptions(USER, new MockHttpServletRequest()))
                .contains("\"challenge\"");
        USER.setDisplayName(null);
    }

    @Test
    void completeRegistrationRequiresStartedSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertApiException(
                () -> service.completeRegistration(USER, "{}", request),
                400,
                "PASSKEY_INCOMPLETE");
    }

    @Test
    void completeRegistrationRejectsUnreadableStoredOptions() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("PASSKEY_REGISTRATION_OPTIONS", "bukan-json");
        String body =
                FakeAuthenticator.create(
                                7,
                                PasskeyCredentialRepository.uuidToBytes(USER_ID).getBytes(),
                                "localhost",
                                "http://localhost:5173")
                        .registrationResponseJson("Y2hhbGxlbmdl");

        assertApiException(
                () -> service.completeRegistration(USER, body, request),
                400,
                "PASSKEY_INCOMPLETE");
    }

    @Test
    void completeRegistrationRejectsInvalidBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        service.createRegistrationOptions(USER, request);

        assertApiException(
                () -> service.completeRegistration(USER, "bukan-json", request),
                400,
                "PASSKEY_INVALID");
    }

    @Test
    void createLoginOptionsStoresAssertionRequestInSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        String json = service.createLoginOptions(request);

        assertThat(json).contains("\"challenge\"");
        assertThat(request.getSession(false).getAttribute("PASSKEY_ASSERTION_REQUEST"))
                .isNotNull();
    }

    @Test
    void loginRequiresStartedSession() {
        assertApiException(
                () -> service.login("{}", new MockHttpServletRequest(), new MockHttpServletResponse()),
                400,
                "PASSKEY_INCOMPLETE");
    }

    @Test
    void loginRejectsUnreadableStoredAssertionRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("PASSKEY_ASSERTION_REQUEST", "bukan-json");

        assertApiException(
                () -> service.login("{}", request, new MockHttpServletResponse()),
                400,
                "PASSKEY_INCOMPLETE");
    }

    @Test
    void loginRejectsInvalidBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        service.createLoginOptions(request);

        assertApiException(
                () -> service.login("bukan-json", request, new MockHttpServletResponse()),
                400,
                "PASSKEY_INVALID");
    }

    @Test
    void loginResolvesClientIpFromForwardedHeaders() {
        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        assertApiException(
                () -> service.login("{}", forwarded, new MockHttpServletResponse()),
                400,
                "PASSKEY_INCOMPLETE");
        verify(rateLimiter).assertAttemptAllowed("1.2.3.4", null, "passkey-login");

        MockHttpServletRequest realIp = new MockHttpServletRequest();
        realIp.addHeader("X-Real-IP", "9.9.9.9");
        assertApiException(
                () -> service.login("{}", realIp, new MockHttpServletResponse()),
                400,
                "PASSKEY_INCOMPLETE");
        verify(rateLimiter).assertAttemptAllowed("9.9.9.9", null, "passkey-login");

        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("127.0.0.1");
        assertApiException(
                () -> service.login("{}", remote, new MockHttpServletResponse()),
                400,
                "PASSKEY_INCOMPLETE");
        verify(rateLimiter).assertAttemptAllowed("127.0.0.1", null, "passkey-login");

        // Header ada tetapi kosong, jatuh ke remoteAddr (bukan string kosong).
        MockHttpServletRequest emptyForwarded = new MockHttpServletRequest();
        emptyForwarded.addHeader("X-Forwarded-For", "");
        emptyForwarded.addHeader("X-Real-IP", "");
        assertApiException(
                () -> service.login("{}", emptyForwarded, new MockHttpServletResponse()),
                400,
                "PASSKEY_INCOMPLETE");
    }

    @Test
    void maskEmailHandlesNullWithoutAtAndShortLocalPart() {
        assertThat(PasskeyService.maskEmail(null)).isEqualTo("***");
        assertThat(PasskeyService.maskEmail("tanpa-at")).isEqualTo("***");
        assertThat(PasskeyService.maskEmail("a@b.c")).isEqualTo("***@b.c");
        assertThat(PasskeyService.maskEmail("pemilik@example.com")).isEqualTo("p***@example.com");
    }

    @Test
    void loginRejectsRejectedAssertionResult() throws Exception {
        RelyingParty party = mock(RelyingParty.class);
        AssertionResult rejected = mock(AssertionResult.class);
        when(rejected.isSuccess()).thenReturn(false);
        when(party.finishAssertion(any())).thenReturn(rejected);
        PasskeyService mocked = serviceUsing(party);

        MockHttpServletRequest request = assertionSession();
        assertApiException(
                () -> mocked.login(assertionBody(), request, new MockHttpServletResponse()),
                401,
                "UNAUTHORIZED");
    }

    @Test
    void loginRejectsUnknownAndShortUsernamesFromAssertion() throws Exception {
        RelyingParty party = mock(RelyingParty.class);
        AssertionResult result = mock(AssertionResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(party.finishAssertion(any())).thenReturn(result);
        PasskeyService mocked = serviceUsing(party);

        when(result.getUsername()).thenReturn(null);
        when(users.findByEmail("")).thenReturn(Optional.empty());
        assertApiException(
                () -> mocked.login(assertionBody(), assertionSession(), new MockHttpServletResponse()),
                401,
                "UNAUTHORIZED");

        when(result.getUsername()).thenReturn("a@b.c");
        when(users.findByEmail("a@b.c")).thenReturn(Optional.empty());
        assertApiException(
                () -> mocked.login(assertionBody(), assertionSession(), new MockHttpServletResponse()),
                401,
                "UNAUTHORIZED");
    }

    @Test
    void completeRegistrationRejectsCredentialAlreadyStored() throws Exception {
        RelyingParty party = mock(RelyingParty.class);
        RegistrationResult result = mock(RegistrationResult.class);
        byte[] credentialId = {9, 8, 7};
        PublicKeyCredentialDescriptor descriptor =
                PublicKeyCredentialDescriptor.builder()
                        .id(new ByteArray(credentialId))
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .build();
        when(result.getKeyId()).thenReturn(descriptor);
        when(party.finishRegistration(any())).thenReturn(result);
        Passkey existing = new Passkey(UUID.randomUUID(), USER_ID, credentialId, new byte[] {1});
        when(passkeys.findByCredentialId(credentialId)).thenReturn(Optional.of(existing));
        PasskeyService mocked = serviceUsing(party);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("PASSKEY_REGISTRATION_OPTIONS", registrationOptions());

        assertApiException(
                () ->
                        mocked.completeRegistration(
                                USER,
                                FakeAuthenticator.create(
                                                7,
                                                PasskeyCredentialRepository.uuidToBytes(USER_ID)
                                                        .getBytes(),
                                                "localhost",
                                                "http://localhost:5173")
                                        .registrationResponseJson("Y2hhbGxlbmdl"),
                                request),
                409,
                "CONFLICT");
    }

    private PasskeyService serviceUsing(RelyingParty party) {
        WebAuthnServer server = mock(WebAuthnServer.class);
        when(server.get()).thenReturn(party);
        return new PasskeyService(
                server, properties, passkeys, users, securityContextRepository, rateLimiter);
    }

    private RelyingParty realParty() {
        return new WebAuthnServer(properties, new PasskeyCredentialRepository(passkeys, users))
                .get();
    }

    private String registrationOptions() throws Exception {
        return realParty()
                .startRegistration(
                        StartRegistrationOptions.builder()
                                .user(
                                        UserIdentity.builder()
                                                .name(USER.getEmail())
                                                .displayName(USER.getEmail())
                                                .id(PasskeyCredentialRepository.uuidToBytes(USER_ID))
                                                .build())
                                .build())
                .toJson();
    }

    private MockHttpServletRequest assertionSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AssertionRequest assertion = realParty().startAssertion(StartAssertionOptions.builder().build());
        request.getSession(true).setAttribute("PASSKEY_ASSERTION_REQUEST", assertion.toJson());
        return request;
    }

    private String assertionBody() throws Exception {
        return FakeAuthenticator.create(
                        7,
                        PasskeyCredentialRepository.uuidToBytes(USER_ID).getBytes(),
                        "localhost",
                        "http://localhost:5173")
                .assertionResponseJson("Y2hhbGxlbmdl", false);
    }

    @Test
    void listMapsRowsAndUsesFallbackLabel() {
        Passkey fallback =
                new Passkey(
                        UUID.randomUUID(),
                        USER_ID,
                        new byte[] {1},
                        new byte[] {(byte) 0xA1, 0x03, 0x26});
        fallback.setLastUsedAt(Instant.parse("2026-10-31T00:00:00Z"));
        Passkey labelled =
                new Passkey(
                        UUID.randomUUID(),
                        USER_ID,
                        new byte[] {2},
                        new byte[] {(byte) 0xA1, 0x03, 0x26});
        labelled.setLabel("iPhone");
        labelled.setBackupState(true);
        when(passkeys.findByUserIdOrderByCreatedAtAsc(USER_ID))
                .thenReturn(List.of(fallback, labelled));

        List<PasskeyDto> rows = service.list(USER_ID);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).label()).isEqualTo("Perangkat ini");
        assertThat(rows.get(0).backedUp()).isFalse();
        assertThat(rows.get(0).lastUsedAt()).isEqualTo(Instant.parse("2026-10-31T00:00:00Z"));
        assertThat(rows.get(1).label()).isEqualTo("iPhone");
        assertThat(rows.get(1).backedUp()).isTrue();
    }

    @Test
    void deleteRejectsUnknownPasskeyAndDeletesOwnedOne() {
        UUID passkeyId = UUID.randomUUID();
        when(passkeys.findByIdAndUserId(passkeyId, USER_ID)).thenReturn(Optional.empty());
        assertApiException(() -> service.delete(USER_ID, passkeyId), 404, "NOT_FOUND");

        Passkey row =
                new Passkey(passkeyId, USER_ID, new byte[] {1}, new byte[] {2});
        when(passkeys.findByIdAndUserId(passkeyId, USER_ID)).thenReturn(Optional.of(row));
        service.delete(USER_ID, passkeyId);
        verify(passkeys).delete(row);
    }
}
