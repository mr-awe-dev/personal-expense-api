package com.fastwork.expense.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestContextIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;

    private User user;
    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        aspectLogger = (Logger) org.slf4j.LoggerFactory.getLogger(LoggingAspect.class);
        originalLevel = aspectLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
        aspectLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(appender);
        aspectLogger.setLevel(originalLevel);
        MDC.clear();
    }

    @Test
    void publicEndpointEchoesGeneratedRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        RequestCorrelationFilter.HEADER,
                                        matchesPattern("[0-9a-f]{32}")));
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void validInboundRequestIdIsPreservedEndToEnd() throws Exception {
        mockMvc.perform(
                        get("/api/v1/health")
                                .header(RequestCorrelationFilter.HEADER, "trace-abc_123"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestCorrelationFilter.HEADER, "trace-abc_123"));
    }

    @Test
    void invalidInboundRequestIdIsReplacedNotEchoed() throws Exception {
        mockMvc.perform(
                        get("/api/v1/health")
                                .header(RequestCorrelationFilter.HEADER, "'; DROP TABLE users; --"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        RequestCorrelationFilter.HEADER,
                                        matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void authenticatedRequestCarriesRequestIdAndUserIdInMdc() throws Exception {
        Cookie session = login();

        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk());

        assertThat(appender.list)
                .anySatisfy(
                        event -> {
                            Map<String, String> mdc = event.getMDCPropertyMap();
                            assertThat(mdc).containsKey(RequestCorrelationFilter.MDC_REQUEST_ID);
                            assertThat(mdc)
                                    .containsEntry(
                                            UserContextMdcFilter.MDC_USER_ID,
                                            user.getId().toString());
                        });
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(UserContextMdcFilter.MDC_USER_ID)).isNull();
    }

    @Test
    void anonymousRequestHasNoUserIdInMdc() throws Exception {
        appender.list.clear();
        mockMvc.perform(
                        withCsrf(post("/api/v1/auth/login"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "email",
                                                        "pemilik@example.com",
                                                        "password",
                                                        PASSWORD))))
                .andExpect(status().isOk());

        assertThat(appender.list)
                .filteredOn(
                        event ->
                                !event.getMDCPropertyMap()
                                        .containsKey(UserContextMdcFilter.MDC_USER_ID))
                .isNotEmpty();
    }

    private Cookie login() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
                                                        Map.of(
                                                                "email",
                                                                "pemilik@example.com",
                                                                "password",
                                                                PASSWORD))))
                        .andExpect(status().isOk())
                        .andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Session cookie tidak dibuat");
    }

    private String json(Map<String, String> values) throws Exception {
        return objectMapper.writeValueAsString(values);
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        for (Cookie cookie : probe.getResponse().getCookies()) {
            if (CSRF_COOKIE.equals(cookie.getName())) {
                return builder.cookie(cookie).header(CSRF_HEADER, cookie.getValue());
            }
        }
        throw new IllegalStateException("CSRF cookie tidak dibuat");
    }
}
