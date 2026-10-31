package com.fastwork.expense.passkey;

import static com.fastwork.expense.passkey.PasskeyCredentialRepository.uuidToBytes;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.auth.LoginRateLimiter;
import com.fastwork.expense.auth.LoginResponse;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.UuidV7;
import com.fastwork.expense.user.MeResponse;
import com.fastwork.expense.user.PetPrincipal;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sesi WebAuthn: pendaftaran passkey (dalam sesi login, kartu Pengaturan) dan
 * login sekali-tap tanpa email (resident key). Challenge/opsi disimpan sebagai
 * atribut HTTP session (Spring Session JDBC) agar aman restart dan tidak bisa
 * dimanipulasi klien. Setelah login sukses, sesi anonim diputar id-nya
 * (tandingan proteksi session fixation) lalu diisi SecurityContext pengguna.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasskeyService {

    private static final String SESSION_KEY_REGISTRATION = "PASSKEY_REGISTRATION_OPTIONS";
    private static final String SESSION_KEY_ASSERTION = "PASSKEY_ASSERTION_REQUEST";

    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");
    private static final Marker LOGIN_SUCCESS_MARKER = MarkerFactory.getMarker("LOGIN_SUCCESS");
    private static final Marker LOGIN_FAILURE_MARKER = MarkerFactory.getMarker("LOGIN_FAILURE");

    private final WebAuthnServer webAuthnServer;
    private final WebAuthnProperties properties;
    private final PasskeyRepository passkeys;
    private final UserRepository users;
    private final SecurityContextRepository securityContextRepository;
    private final LoginRateLimiter rateLimiter;

    // ---------- Pendaftaran (pengguna sudah masuk) ----------

    public String createRegistrationOptions(User user, HttpServletRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        requireEnabled();
        RelyingParty party = webAuthnServer.get();
        PublicKeyCredentialCreationOptions options =
                party.startRegistration(
                        StartRegistrationOptions.builder()
                                .user(UserIdentity.builder()
                                        .name(user.getEmail())
                                        .displayName(displayNameOf(user))
                                        .id(uuidToBytes(user.getId()))
                                        .build())
                                // Resident key = passkey: dipakai login tanpa perlu email.
                                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                        .residentKey(ResidentKeyRequirement.REQUIRED)
                                        .userVerification(UserVerificationRequirement.PREFERRED)
                                        .build())
                                .build());
        request.getSession(true).setAttribute(SESSION_KEY_REGISTRATION, options.toJson());
        log.info(
                AUTH_MARKER,
                "Passkey registration started: userId={}, email={}",
                user.getId(),
                maskEmail(user.getEmail()));
        return options.toCredentialsCreateJson();
    }

    @Transactional
    public PasskeyDto completeRegistration(User user, String body, HttpServletRequest request) {
        requireEnabled();
        HttpSession session = request.getSession(false);
        String storedOptions =
                session == null ? null : (String) session.getAttribute(SESSION_KEY_REGISTRATION);
        if (storedOptions == null) {
            throw ApiException.badRequest("PASSKEY_INCOMPLETE", "Pendaftaran passkey belum dimulai");
        }
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs>
                credential = parseRegistrationResponse(body);
        RelyingParty party = webAuthnServer.get();

        RegistrationResult result;
        try {
            result =
                    party.finishRegistration(
                            FinishRegistrationOptions.builder()
                                    .request(
                                            parseStoredRegistrationOptions(storedOptions))
                                    .response(credential)
                                    .build());
        } catch (RegistrationFailedException ex) {
            log.warn(
                    LOGIN_FAILURE_MARKER,
                    "Passkey registration failed: userId={}, reason={}",
                    user.getId(),
                    ex.getMessage());
            throw ApiException.badRequest("PASSKEY_VERIFY_FAILED", "Verifikasi passkey gagal");
        }

        session.removeAttribute(SESSION_KEY_REGISTRATION);
        byte[] credentialIdBytes = result.getKeyId().getId().getBytes();
        if (passkeys.findByCredentialId(credentialIdBytes).isPresent()) {
            throw ApiException.conflict("Passkey sudah terdaftar");
        }
        Passkey row =
                new Passkey(
                        UuidV7.random(),
                        user.getId(),
                        credentialIdBytes,
                        result.getPublicKeyCose().getBytes());
        row.setAlg(PasskeyCredentialRepository.algFromCose(result.getPublicKeyCose().getBytes()));
        row.setSignatureCount(result.getSignatureCount());
        row.setBackupEligible(result.isBackupEligible());
        row.setBackupState(result.isBackedUp());
        row.setDiscoverable(result.isDiscoverable().orElse(true));
        row.setTransports(
                PasskeyCredentialRepository.transportsToString(
                        credential.getResponse().getTransports()));
        passkeys.save(row);
        log.info(
                AUTH_MARKER,
                "Passkey registered: userId={}, discoverable={}, backupEligible={}",
                user.getId(),
                row.getDiscoverable(),
                row.getBackupEligible());
        return PasskeyDto.from(row, FALLBACK_LABEL);
    }

    // ---------- Daftar & hapus ----------

    @Transactional(readOnly = true)
    public List<PasskeyDto> list(UUID userId) {
        requireEnabled();
        List<PasskeyDto> result = new ArrayList<>();
        for (Passkey row : passkeys.findByUserIdOrderByCreatedAtAsc(userId)) {
            result.add(PasskeyDto.from(row, FALLBACK_LABEL));
        }
        return result;
    }

    public void delete(UUID userId, UUID passkeyId) {
        requireEnabled();
        Passkey row =
                passkeys
                        .findByIdAndUserId(passkeyId, userId)
                        .orElseThrow(() -> ApiException.notFound("Passkey tidak ditemukan"));
        passkeys.delete(row);
        log.info(AUTH_MARKER, "Passkey deleted: userId={}, passkeyId={}", userId, passkeyId);
    }

    // ---------- Login sekali-tap (tanpa email, anonim) ----------

    public String createLoginOptions(HttpServletRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        requireEnabled();
        RelyingParty party = webAuthnServer.get();
        AssertionRequest assertion =
                party.startAssertion(StartAssertionOptions.builder().build());
        request.getSession(true).setAttribute(SESSION_KEY_ASSERTION, assertion.toJson());
        return assertion.toCredentialsGetJson();
    }

    @Transactional
    public LoginResponse login(
            String body, HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        String ip = resolveClientIp(request);
        log.info(AUTH_MARKER, "Passkey login attempt: ip={}", ip);
        rateLimiter.assertAttemptAllowed(ip, null, "passkey-login");

        HttpSession session = request.getSession(false);
        String storedRequest =
                session == null ? null : (String) session.getAttribute(SESSION_KEY_ASSERTION);
        if (storedRequest == null) {
            throw ApiException.badRequest("PASSKEY_INCOMPLETE", "Login passkey belum dimulai");
        }
        AssertionRequest assertionRequest = parseStoredAssertionRequest(storedRequest);
        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs>
                credential = parseAssertionResponse(body);

        AssertionResult result;
        try {
            result =
                    webAuthnServer
                            .get()
                            .finishAssertion(
                                    FinishAssertionOptions.builder()
                                            .request(assertionRequest)
                                            .response(credential)
                                            .build());
        } catch (AssertionFailedException ex) {
            log.warn(
                    LOGIN_FAILURE_MARKER,
                    "Passkey login failed (signature invalid): reason={}",
                    ex.getMessage());
            throw ApiException.unauthorized("Verifikasi passkey gagal");
        }
        if (!result.isSuccess()) {
            log.warn(LOGIN_FAILURE_MARKER, "Passkey login failed (assertion rejected)");
            throw ApiException.unauthorized("Verifikasi passkey gagal");
        }

        User user =
                users
                        .findByEmail(usernameOf(result))
                        .filter(User::isActive)
                        .orElse(null);
        if (user == null) {
            log.warn(
                    LOGIN_FAILURE_MARKER,
                    "Passkey login failed (user unknown/inactive): owner={}",
                    maskEmail(usernameOf(result)));
            throw ApiException.unauthorized("Passkey tidak dapat dipakai untuk masuk");
        }

        // Baris wajib ada: pemilik kredensial sudah lolos lookup di repositori.
        Passkey row =
                passkeys
                        .findByCredentialId(result.getCredentialId().getBytes())
                        .orElse(null);
        if (row != null) {
            row.setSignatureCount(result.getSignatureCount());
            row.setBackupState(result.isBackedUp());
            row.setLastUsedAt(Instant.now());
            passkeys.save(row);
        }
        rateLimiter.recordSuccess(result.getUsername(), "passkey-login");

        // Sesi anonim pembawa challenge tidak boleh dibawa ke sesi terautentikasi.
        session.removeAttribute(SESSION_KEY_ASSERTION);
        request.changeSessionId();

        establishSession(user, request, response);
        MDC.put("userId", String.valueOf(user.getId()));

        log.info(
                LOGIN_SUCCESS_MARKER,
                "Login successful via passkey: userId={}, email={}, ip={}, sessionId={}",
                user.getId(),
                maskEmail(user.getEmail()),
                ip,
                request.getSession(false).getId());

        return new LoginResponse(MeResponse.from(user));
    }

    // ---------- Helper ----------

    private void establishSession(
            User user, HttpServletRequest request, HttpServletResponse response) {
        PetPrincipal principal = new PetPrincipal(user);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.getSession(true);
        securityContextRepository.saveContext(context, request, response);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw ApiException.notFound("Fitur passkey tidak aktif");
        }
    }

    private static String usernameOf(AssertionResult result) {
        String username = result.getUsername();
        return username == null ? "" : username.trim();
    }

    private static String displayNameOf(User user) {
        if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
            return user.getEmail();
        }
        return user.getDisplayName();
    }

    private static final String FALLBACK_LABEL = "Perangkat ini";

    private PublicKeyCredentialCreationOptions parseStoredRegistrationOptions(String json) {
        try {
            return PublicKeyCredentialCreationOptions.fromJson(json);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            log.warn("Opsi pendaftaran tersimpan tidak bisa dibaca: {}", ex.getMessage());
            throw ApiException.badRequest("PASSKEY_INCOMPLETE", "Pendaftaran passkey belum dimulai");
        }
    }

    private AssertionRequest parseStoredAssertionRequest(String json) {
        try {
            return AssertionRequest.fromJson(json);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            log.warn("Permintaan assertion tersimpan tidak bisa dibaca: {}", ex.getMessage());
            throw ApiException.badRequest("PASSKEY_INCOMPLETE", "Login passkey belum dimulai");
        }
    }

    private PublicKeyCredential<
                    AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs>
            parseRegistrationResponse(String body) {
        try {
            return PublicKeyCredential.parseRegistrationResponseJson(body);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Body registrasi passkey tidak valid: {}", ex.getMessage());
            throw ApiException.badRequest("PASSKEY_INVALID", "Data passkey tidak valid");
        }
    }

    private PublicKeyCredential<
                    AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs>
            parseAssertionResponse(String body) {
        try {
            return PublicKeyCredential.parseAssertionResponseJson(body);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Body assertion passkey tidak valid: {}", ex.getMessage());
            throw ApiException.badRequest("PASSKEY_INVALID", "Data passkey tidak valid");
        }
    }

    /**
     * IP asli klien di belakang Caddy/Function Cloudflare; pola sama dengan
     * {@code AuthService.resolveClientIp}.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf("@");
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
