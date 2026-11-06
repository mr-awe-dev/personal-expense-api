package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.email.EmailMessage;
import com.fastwork.expense.email.EmailSender;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Alur minta OTP pemulihan password via email: jawaban generik untuk email tak dikenal, surat
 * terkirim ke email yang terdaftar, dan kode tidak pernah muncul di log maupun pesan error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForgotPasswordFlowTest extends PostgresIntegrationTest {

    private static final String EMAIL = "pemilik@example.com";
    private static final String PASSWORD = "kpassword-12345";
    private static final String NEW_PASSWORD = "password-baru-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";

    private static final Pattern OTP_PATTERN = Pattern.compile("\\b\\d{8}\\b");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private PasswordResetRepository passwordResetRepository;
    @Autowired private LoginRateLimiter rateLimiter;
    @Autowired private PasswordResetService passwordResetService;

    @MockitoBean private EmailSender emailSender;

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    private Logger authLogger;

    private User user;

    @BeforeEach
    void setUp() {
        passwordResetRepository.deleteAll();
        userRepository.deleteAll();
        user = userService.createUser(EMAIL, PASSWORD);
        authLogger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        logAppender.start();
        authLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        authLogger.detachAppender(logAppender);
        logAppender.stop();
        // Limiter disetel lewat setter pada tes khusus; pastikan mati lagi untuk tes lain.
        rateLimiter.setEnabled(false);
    }

    @Test
    void requestCodeSendsOtpToRegisteredAddressAndResetsPassword() throws Exception {
        forgotRequest(EMAIL, 204);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        EmailMessage message = captor.getValue();
        assertThat(message.to()).isEqualTo(EMAIL);
        assertThat(message.subject()).contains("Kode pemulihan");
        assertThat(OTP_PATTERN.matcher(message.text()).find()).isTrue();

        String code = otpFrom(message.text());
        resetRequest(emailCodePasswordJson(EMAIL, code, NEW_PASSWORD), 204);

        login(EMAIL, PASSWORD, 401);
        login(EMAIL, NEW_PASSWORD, 200);
    }

    @Test
    void otpIsNeverLogged() throws Exception {
        forgotRequest(EMAIL, 204);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        String code = otpFrom(captor.getValue().text());
        assertThat(code).isNotBlank();

        // Kode OTP tidak pernah boleh bocor ke log apa pun (interceptor logback di auth).
        assertThat(logAppender.list).isNotEmpty();
        for (ILoggingEvent event : logAppender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(code);
        }
        assertThat(logAppender.list).anyMatch(e -> e.getLevel() == Level.INFO);
    }

    @Test
    void unknownEmailAnswerIsIdenticalAndSendsNothing() throws Exception {
        forgotRequest("hantu@example.com", 204);

        verify(emailSender, never()).send(any());
        assertThat(passwordResetRepository.count()).isZero();
    }

    @Test
    void blankEmailIsRejectedValidationAndUnknownEmailStaysGeneric() throws Exception {
        forgotRequest(" ", 400);
        assertThat(passwordResetRepository.count()).isZero();

        forgotRequest("ngasal@example.com", 204);
        verify(emailSender, never()).send(any());
        assertThat(passwordResetRepository.count()).isZero();
    }

    @Test
    void repeatedRequestsInvalidatePreviousOtp() throws Exception {
        forgotRequest(EMAIL, 204);
        forgotRequest(EMAIL, 204);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(2)).send(captor.capture());
        List<EmailMessage> messages = captor.getAllValues();
        String first = otpFrom(messages.get(0).text());
        String second = otpFrom(messages.get(1).text());
        assertThat(first).isNotEqualTo(second);

        resetRequest(emailCodePasswordJson(EMAIL, first, NEW_PASSWORD), 400, "INVALID_RESET_CODE");
        resetRequest(emailCodePasswordJson(EMAIL, second, NEW_PASSWORD), 204);
    }

    @Test
    void rateLimiterBoundsOtpEmailStorm() throws Exception {
        rateLimiter.setEnabled(true);
        rateLimiter.setEmailRequestsPerWindow(1);

        forgotRequest(EMAIL, 204);
        verify(emailSender, times(1)).send(any());

        forgotRequest(EMAIL, org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
        // Request ke-2 tidak boleh menerbitkan kode maupun mengirim surat.
        verify(emailSender, times(1)).send(any());
        assertThat(passwordResetRepository.count()).isEqualTo(1);
    }

    @Test
    void cliIssuedCodeStillConsumableAlongsideEmailFlow() throws Exception {
        // Jalur darurat CLI tetap hidup di samping alur email.
        forgotRequest(EMAIL, 204);
        verify(emailSender, times(1)).send(any());

        String cliCode = passwordResetService.issue(EMAIL);
        assertThat(cliCode).matches("\\d{8}");

        resetRequest(emailCodePasswordJson(EMAIL, cliCode, NEW_PASSWORD), 204);
        login(EMAIL, NEW_PASSWORD, 200);
    }

    private String otpFrom(String text) {
        var matcher = OTP_PATTERN.matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    private void forgotRequest(String email, int expectedStatus) throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ForgotPasswordRequest(email))))
                .andExpect(status().is(expectedStatus));
    }

    private void resetRequest(String json, int expectedStatus) throws Exception {
        resetRequest(json, expectedStatus, null);
    }

    private void resetRequest(String json, int expectedStatus, String expectedCode)
            throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/reset-password"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andExpect(status().is(expectedStatus))
                        .andReturn();
        if (expectedCode != null) {
            assertThat(readCode(result)).isEqualTo(expectedCode);
        }
    }

    private String readCode(MvcResult result) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("code")
                .asText();
    }

    private String emailCodePasswordJson(String email, String code, String password)
            throws Exception {
        return objectMapper.writeValueAsString(new ResetPasswordRequest(email, code, password));
    }

    private Cookie login(String email, String password, int expectedStatus) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new LoginRequest(email, password))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(csrf).header(CSRF_HEADER, csrf.getValue());
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        for (Cookie cookie : probe.getResponse().getCookies()) {
            if (CSRF_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Cookie " + CSRF_COOKIE + " tidak dibuat");
    }
}
