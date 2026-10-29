package com.fastwork.expense.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private CategoryService categoryService;

    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    void createExpenseAndIncomeCategories() throws Exception {
        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "EXPENSE", "name", "Makanan"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.name").value("Makanan"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.archived").value(false));

        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "INCOME", "name", "Gaji"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INCOME"));
    }

    @Test
    void duplicateNameIsRejectedCaseInsensitive() throws Exception {
        categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "EXPENSE", "name", "MAKANAN "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void createRejectsInvalidNames() throws Exception {
        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "EXPENSE", "name", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "EXPENSE", "name", "a".repeat(51)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsMissingType() throws Exception {
        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", "Makanan"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listFiltersByTypeAndArchived() throws Exception {
        Category expense = categoryService.create(user.getId(), TxType.EXPENSE, "Transport");
        categoryService.create(user.getId(), TxType.INCOME, "Gaji");

        mockMvc.perform(get("/api/v1/categories").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/categories").cookie(session).param("type", "EXPENSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(
                        authorized(patch("/api/v1/categories/" + expense.getId()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("archived", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        mockMvc.perform(get("/api/v1/categories").cookie(session).param("type", "EXPENSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(
                        get("/api/v1/categories")
                                .cookie(session)
                                .param("type", "EXPENSE")
                                .param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].archived").value(true));
    }

    @Test
    void renameWorksAndDetectsConflict() throws Exception {
        Category expense = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        categoryService.create(user.getId(), TxType.EXPENSE, "Transport");

        mockMvc.perform(
                        authorized(patch("/api/v1/categories/" + expense.getId()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", "Makan dan Minum"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Makan dan Minum"));

        mockMvc.perform(
                        authorized(patch("/api/v1/categories/" + expense.getId()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", "transport"))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateRejectsUnknownCategory() throws Exception {
        mockMvc.perform(
                        authorized(
                                        patch(
                                                "/api/v1/categories/00000000-0000-0000-0000-000000000000"),
                                        session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", "Antah Berantah"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteUnusedCategorySucceeds() throws Exception {
        Category unused = categoryService.create(user.getId(), TxType.EXPENSE, "Belum Dipakai");
        mockMvc.perform(authorized(delete("/api/v1/categories/" + unused.getId()), session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/categories").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteRejectsUnknownCategory() throws Exception {
        mockMvc.perform(
                        authorized(
                                delete("/api/v1/categories/00000000-0000-0000-0000-000000000000"),
                                session))
                .andExpect(status().isNotFound());
    }

    private Cookie login(String email, String password) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(Map.of("email", email, "password", password))))
                        .andExpect(status().isOk())
                        .andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Session cookie tidak dibuat");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(csrf).header(CSRF_HEADER, csrf.getValue());
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, Cookie session) throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(session, csrf).header(CSRF_HEADER, csrf.getValue());
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

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
