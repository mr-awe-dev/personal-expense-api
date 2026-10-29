package com.fastwork.expense.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceiptScanFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";

    private static final String SAMPLE_OCR =
            """
            WARUNG KOPI NUSANTARA
            Jalan Melati 12
            24/10/2026  14:32
            Kopi Susu 18.000
            Roti Bakar 27.000
            TOTAL
            Rp 45.000
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;

    @MockitoBean private TextExtractor extractor;

    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        userService.createUser("pemilik@example.com", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
    }

    @Test
    void scanRequiresAuthentication() throws Exception {
        Cookie csrf = csrfCookie();
        mockMvc.perform(
                        multipart("/api/v1/receipts/scan")
                                .file(image("struk.jpg", "image/jpeg"))
                                .cookie(csrf)
                                .header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingFilePartIsMalformed() throws Exception {
        mockMvc.perform(authorized(multipart("/api/v1/receipts/scan"), session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyFileRejected() throws Exception {
        mockMvc.perform(
                        authorized(
                                multipart("/api/v1/receipts/scan")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "kosong.jpg",
                                                        "image/jpeg",
                                                        new byte[0])),
                                session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
    }

    @Test
    void nonImageContentTypeRejected() throws Exception {
        mockMvc.perform(
                        authorized(
                                multipart("/api/v1/receipts/scan")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "catatan.txt",
                                                        MediaType.TEXT_PLAIN_VALUE,
                                                        "halo".getBytes(StandardCharsets.UTF_8))),
                                session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
    }

    @Test
    void happyPathReturnsParsedFields() throws Exception {
        given(extractor.extract(any())).willReturn(SAMPLE_OCR);

        mockMvc.perform(
                        authorized(
                                multipart("/api/v1/receipts/scan")
                                        .file(image("struk.jpg", "image/jpeg")),
                                session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(45000))
                .andExpect(jsonPath("$.date").value("2026-10-24"))
                .andExpect(jsonPath("$.description").value("WARUNG KOPI NUSANTARA"))
                .andExpect(jsonPath("$.confidence").value(100));
    }

    @Test
    void unreadableOcrMapsToUnprocessable() throws Exception {
        willThrow(new ReceiptOcrException("simulasi gagal")).given(extractor).extract(any());

        mockMvc.perform(
                        authorized(
                                multipart("/api/v1/receipts/scan")
                                        .file(image("struk.png", "image/png")),
                                session))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEIPT_UNREADABLE"));
    }

    @Test
    void blankOcrTextMapsToUnprocessable() throws Exception {
        given(extractor.extract(any())).willReturn("   \n  ");

        mockMvc.perform(
                        authorized(
                                multipart("/api/v1/receipts/scan")
                                        .file(image("struk.jpg", "image/jpeg")),
                                session))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEIPT_UNREADABLE"));
    }

    @Test
    void responseShapeSerializesNullsAsAbsent() throws Exception {
        given(extractor.extract(any())).willReturn("TERIMA KASIH\nSUDAH BELANJA");

        MvcResult result =
                mockMvc.perform(
                                authorized(
                                        multipart("/api/v1/receipts/scan")
                                                .file(image("struk.jpg", "image/jpeg")),
                                        session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.amount").doesNotExist())
                        .andExpect(jsonPath("$.confidence").value(20))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("TERIMA KASIH");
    }

    private MockMultipartFile image(String name, String contentType) {
        return new MockMultipartFile(
                "file", name, contentType, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, Cookie activeSession) throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(activeSession, csrf).header(CSRF_HEADER, csrf.getValue());
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

    private Cookie login(String email, String password) throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf)
                                        .header(CSRF_HEADER, csrf.getValue())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                email,
                                                                "password",
                                                                password))))
                        .andExpect(status().isOk())
                        .andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Session cookie tidak dibuat");
    }
}
