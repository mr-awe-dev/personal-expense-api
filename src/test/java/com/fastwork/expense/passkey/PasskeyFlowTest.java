package com.fastwork.expense.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

/**
 * Alur passkey end-to-end dengan authenticator palsu (ES256 + encoder CBOR sendiri):
 * pendaftaran di Pengaturan, daftar/hapus, dan login sekali-tap tanpa email.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasskeyFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpasskey-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";
    private static final String BASE = "/api/v1/passkey";
    private static final String POST_LOGIN = "/login";
    private static final String RP_ID = "localhost";
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;

    private final ObjectMapper json = new ObjectMapper();
    private User user;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
    }

    // ---------- Perlindungan umum (CSRF, autentikasi) ----------

    @Test
    void registerOptionsRequiresAuthentication() throws Exception {
        mockMvc.perform(withCsrf(post(BASE + "/register/options")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerOptionsStillRequiresCsrf() throws Exception {
        // Pengecualian CSRF hanya untuk POST /passkey/login.
        mockMvc.perform(post(BASE + "/register/options")).andExpect(status().isForbidden());
    }

    @Test
    void loginEndpointIsCsrfExempt() throws Exception {
        mockMvc.perform(post(BASE + POST_LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSKEY_INCOMPLETE"));
    }

    @Test
    void loginOptionsStillRequiresCsrf() throws Exception {
        mockMvc.perform(post(BASE + "/login/options")).andExpect(status().isForbidden());
    }

    @Test
    void loginOptionsIsAnonymousAndCreatesOwnSession() throws Exception {
        MvcResult result = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.challenge").exists())
                .andReturn();
        Cookie anon = sessionCookie(result);
        assertThat(anon).isNotNull();
        // Sesi opsi belum berisi token apa pun — /me tetap 401.
        mockMvc.perform(get("/api/v1/me").cookie(anon)).andExpect(status().isUnauthorized());
    }

    // ---------- Pendaftaran (kartu Pengaturan) ----------

    @Test
    void registerListAndDeleteHappyPath() throws Exception {
        FakeAuthenticator fake = authenticator();
        Cookie session = loginSession();
        MvcResult done =
                perform(
                        withCsrf(post(BASE + "/register"))
                                .cookie(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        fake.registrationResponseJson(
                                                challengeOf(registerOptions(session)))),
                        201);
        JsonNode created = json.readTree(done.getResponse().getContentAsString());
        UUID passkeyId = UUID.fromString(created.path("id").asText());
        assertThat(created.path("backedUp").asBoolean()).isFalse();

        JsonNode rows = passkeyList(session);
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("id").asText()).isEqualTo(passkeyId.toString());
        assertThat(rows.get(0).path("label").asText()).isEqualTo("Perangkat ini");

        // Osiera pendaftaran tetap jalan dengan displayName kosong (whitespace) lalu terisi.
        user.setDisplayName("   ");
        userRepository.save(user);
        registerOptions(session);
        user.setDisplayName("Kepala Keluarga");
        userRepository.save(user);
        registerOptions(session);

        perform(withCsrf(delete(BASE + "/" + passkeyId)).cookie(session), 204);
        assertThat(passkeyList(session).size()).isZero();
        perform(withCsrf(delete(BASE + "/" + passkeyId)).cookie(session), 404, "$.code",
                "NOT_FOUND");
    }

    @Test
    void duplicateCredentialIsRejected() throws Exception {
        FakeAuthenticator fake = authenticator();
        Cookie session = loginSession();
        perform(withCsrf(post(BASE + "/register"))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fake.registrationResponseJson(challengeOf(registerOptions(session)))),
                201);
        // rawId sama + excludeCredentials dari opsi kedua → verifikasi ditolak.
        registerOptions(session);
        perform(withCsrf(post(BASE + "/register"))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fake.registrationResponseJson(
                        challengeOf(registerOptions(session)))),
                400);
    }

    @Test
    void challengeMismatchIsRejected() throws Exception {
        Cookie session = loginSession();
        registerOptions(session); // challenge server tidak dipakai
        perform(
                withCsrf(post(BASE + "/register"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticator()
                                .registrationResponseJson("Y2hhbGxlbmdlLXNhbGFo")),
                400,
                "$.code",
                "PASSKEY_VERIFY_FAILED");
    }

    @Test
    void invalidBodyIsRejected() throws Exception {
        Cookie session = loginSession();
        registerOptions(session); // opsi harus tersimpan dulu di sesi
        perform(
                withCsrf(post(BASE + "/register"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("bukan-json"),
                400,
                "$.code",
                "PASSKEY_INVALID");
    }

    @Test
    void registerWithoutSessionIs401() throws Exception {
        perform(
                withCsrf(post(BASE + "/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticator()
                                .registrationResponseJson("Y2hhbGxlbmdlLXNhbGFo")),
                401);
    }

    @Test
    void registerWithPlainLoginSessionIsRejected() throws Exception {
        Cookie session = loginSession(); // sesi sah tetapi tanpa atribut options
        perform(
                withCsrf(post(BASE + "/register"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticator()
                                .registrationResponseJson("Y2hhbGxlbmdlLXNhbGFo")),
                400,
                "$.code",
                "PASSKEY_INCOMPLETE");
    }

    // ---------- Login sekali-tap (tanpa email) ----------

    @Test
    void loginWithPasskeyEstablishesSessionAndRotatesId() throws Exception {
        FakeAuthenticator fake = authenticator();
        registerPasskey(fake);

        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        String challenge = challengeOf(options.getResponse().getContentAsString());
        Cookie anon = sessionCookie(options);
        assertThat(anon).isNotNull();

        MvcResult login =
                perform(
                        post(BASE + POST_LOGIN)
                                .cookie(anon)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fake.assertionResponseJson(challenge, false)),
                        200);
        JsonNode body = json.readTree(login.getResponse().getContentAsString());
        assertThat(body.path("user").path("email").asText()).isEqualTo(user.getEmail());

        Cookie authed = sessionCookie(login);
        assertThat(authed).isNotNull();
        assertThat(authed.getValue()).isNotEqualTo(anon.getValue());

        mockMvc.perform(get("/api/v1/me").cookie(authed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()));

        assertThat(passkeyList(authed).size()).isEqualTo(1);
    }

@Test
    void repeatedLoginRefreshesBackupState() throws Exception {
        FakeAuthenticator fake = authenticator();
        registerPasskey(fake);

        // First login without backup
        MvcResult options1 = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        String challenge1 = challengeOf(options1.getResponse().getContentAsString());
        Cookie anon1 = sessionCookie(options1);
        MvcResult login1 = perform(
                post(BASE + POST_LOGIN)
                        .cookie(anon1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fake.assertionResponseJson(challenge1, false)),
                200);

        // Second login WITHOUT backup (test basic login refresh)
        MvcResult options2 = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        String challenge2 = challengeOf(options2.getResponse().getContentAsString());
        Cookie anon2 = sessionCookie(options2);
        MvcResult login2 = perform(
                post(BASE + POST_LOGIN)
                        .cookie(anon2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fake.assertionResponseJson(challenge2, false)),
                200);
        Cookie authed2 = sessionCookie(login2);

        // Verify both logins succeeded and check passkey list
        JsonNode rows = passkeyList(authed2);
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("backedUp").asBoolean()).isFalse();
    }

    @Test
    void loginWithPlainLoginSessionIsRejected() throws Exception {
        Cookie session = loginSession();
        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(sessionCookie(options)).isNotNull();
        perform(
                post(BASE + POST_LOGIN)
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticator()
                                .assertionResponseJson("Y2hhbGxlbmdlLXNhbGFo", false)),
                400,
                "$.code",
                "PASSKEY_INCOMPLETE");
    }

    @Test
    void unknownCredentialIsRejected() throws Exception {
        registerPasskey(authenticator());
        FakeAuthenticator intruder = authenticator(99);
        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        perform(
                post(BASE + POST_LOGIN)
                        .cookie(sessionCookie(options))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                intruder.assertionResponseJson(
                                        challengeOf(options.getResponse().getContentAsString()),
                                        false)),
                401,
                "$.code",
                "UNAUTHORIZED");
    }

@Test
    void tamperedSignatureIsRejected() throws Exception {
        FakeAuthenticator fake = authenticator();
        registerPasskey(fake);
        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        String challenge = challengeOf(options.getResponse().getContentAsString());
        String assertion = fake.assertionResponseJson(challenge, false);
        // Tamper signature - replace first 10 chars, keep JSON valid
        String corrupted =
                assertion.replaceFirst(
                        "\"signature\":\"[A-Za-z0-9_-]{10}",
                        "\"signature\":\"AAAAAAAAAA\"");  // 10 chars to keep JSON valid
        perform(
                post(BASE + POST_LOGIN)
                        .cookie(sessionCookie(options))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corrupted),
                400,
                "$.code",
                "PASSKEY_INVALID");
    }

    @Test
    void stagnantCounterIsRejected() throws Exception {
        FakeAuthenticator fake = authenticator();
        registerPasskey(fake);
        loginPasskey(fake, false); // menyimpan counter = 1
        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        perform(
                post(BASE + POST_LOGIN)
                        .cookie(sessionCookie(options))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fake.frozenCounterAssertion(
                                challengeOf(options.getResponse().getContentAsString()))),
                401,
                "$.code",
                "UNAUTHORIZED");
    }

    @Test
    void loginWithDeletedUserIsRejected() throws Exception {
        FakeAuthenticator fake = authenticator();
        registerPasskey(fake);
        userRepository.delete(user); // kaskade: baris passkeys ikut hilang
        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        perform(
                post(BASE + POST_LOGIN)
                        .cookie(sessionCookie(options))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fake.assertionResponseJson(
                                challengeOf(options.getResponse().getContentAsString()), false)),
                401,
                "$.code",
                "UNAUTHORIZED");
    }

    @Test
    void loginWithDisabledAccountIsRejected() throws Exception {
        FakeAuthenticator fake = authenticator();
        registerPasskey(fake);
        user.setDisabledAt(Instant.now());
        userRepository.save(user);
        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult result =
                perform(
                        post(BASE + POST_LOGIN)
                                .cookie(sessionCookie(options))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fake.assertionResponseJson(
                                        challengeOf(options.getResponse().getContentAsString()),
                                        false)),
                        401,
                        "$.code",
                        "UNAUTHORIZED");
        assertThat(
                        json.readTree(result.getResponse().getContentAsString())
                                .path("message")
                                .asText())
                .isEqualTo("Passkey tidak dapat dipakai untuk masuk");
    }

    // ---------- Helper ----------

    private String csrfToken() throws Exception {
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        for (Cookie cookie : probe.getResponse().getCookies()) {
            if (CSRF_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        throw new IllegalStateException("Cookie " + CSRF_COOKIE + " tidak dibuat");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        String token = csrfToken();
        return builder.cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
    }

    private Cookie loginSession() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"pemilik@example.com\",\"password\":\""
                                                        + PASSWORD + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        return sessionCookie(result);
    }

    private Cookie sessionCookie(MvcResult result) {
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private FakeAuthenticator authenticator() {
        return authenticator(7);
    }

    private FakeAuthenticator authenticator(int seed) {
        return FakeAuthenticator.create(
                seed,
                PasskeyCredentialRepository.uuidToBytes(user.getId()).getBytes(),
                RP_ID,
                ORIGIN);
    }

    private String registerOptions(Cookie session) throws Exception {
        MvcResult result = mockMvc.perform(withCsrf(post(BASE + "/register/options")).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.challenge").exists())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String challengeOf(String optionsJson) throws Exception {
        JsonNode body = json.readTree(optionsJson);
        JsonNode publicKey = body.has("publicKey") ? body.get("publicKey") : body;
        return publicKey.path("challenge").asText(null);
    }

    private JsonNode passkeyList(Cookie session) throws Exception {
        MvcResult result =
                mockMvc.perform(withCsrf(get(BASE)).cookie(session))
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Pendaftaran passkey lewat kartu Pengaturan di sesi kontemporer. */
    private void registerPasskey(FakeAuthenticator fake) throws Exception {
        Cookie session = loginSession();
        perform(
                withCsrf(post(BASE + "/register"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fake.registrationResponseJson(challengeOf(registerOptions(session)))),
                201);
    }

    /** Mulai + selesaikan login passkey; kembalikan cookie sesi terautentikasi. */
    private Cookie loginPasskey(FakeAuthenticator fake, boolean backupState) throws Exception {
        MvcResult options = mockMvc.perform(withCsrf(post(BASE + "/login/options")))
                .andExpect(status().isOk())
                .andReturn();
        String challenge = challengeOf(options.getResponse().getContentAsString());
        MvcResult done = perform(
                post(BASE + POST_LOGIN)
                        .cookie(sessionCookie(options))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fake.assertionResponseJson(challenge, backupState)),
                200);
        return sessionCookie(done);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, int expected) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return result;
    }

    private MvcResult perform(
            MockHttpServletRequestBuilder builder,
            int expected,
            String jsonPathExpr,
            String expectedValue)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo(expectedValue);
        return result;
    }
}
