package com.fastwork.expense.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import com.fastwork.expense.category.Category;
import com.fastwork.expense.category.CategoryService;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.common.UuidV7;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

/** Menutup jalur keyset pagination per sort + cursor rusak + cabang PATCH per-field. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionCursorFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;

    private User user;
    private Cookie session;
    private Category makanan;
    private Category minuman;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
        makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        minuman = categoryService.create(user.getId(), TxType.EXPENSE, "Minuman");
    }

    private void putTx(UUID id, String date, Category category, String description, long amount)
            throws Exception {
        Map<String, Object> body =
                category == null
                        ? Map.of(
                                "date", date,
                                "type", "EXPENSE",
                                "description", description,
                                "amount", amount)
                        : Map.of(
                                "date", date,
                                "type", "EXPENSE",
                                "categoryId", category.getId().toString(),
                                "description", description,
                                "amount", amount);
        mockMvc.perform(
                        authorized(put("/api/v1/transactions/" + id), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private JsonNode page(String sort, String cursor, int limit) throws Exception {
        MockHttpServletRequestBuilder builder =
                get("/api/v1/transactions").param("sort", sort).param("limit", String.valueOf(limit));
        if (cursor != null) {
            builder = builder.param("cursor", cursor);
        }
        MvcResult result =
                mockMvc.perform(builder.cookie(session)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void amountSortPagesWalkEveryRowExactlyOnce() throws Exception {
        for (int i = 1; i <= 5; i++) {
            putTx(UuidV7.random(), "2026-10-0" + i, makanan, "transaksi-" + i, 1000L * i);
        }
        for (String sort : List.of("AMOUNT_DESC", "AMOUNT_ASC")) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            do {
                JsonNode root = page(sort, cursor, 2);
                root.get("items").forEach(item -> seen.add(item.get("description").asText()));
                cursor = root.hasNonNull("nextCursor") ? root.get("nextCursor").asText() : null;
            } while (cursor != null);
            assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        }
    }

    @Test
    void categorySortPagesIncludeUncategorizedRows() throws Exception {
        putTx(UuidV7.random(), "2026-10-01", makanan, "nasi", 5000);
        putTx(UuidV7.random(), "2026-10-02", null, "lupalah", 6000);
        putTx(UuidV7.random(), "2026-10-03", minuman, "es", 7000);

        for (String sort : List.of("CATEGORY_ASC", "CATEGORY_DESC")) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            do {
                JsonNode root = page(sort, cursor, 1);
                root.get("items").forEach(item -> seen.add(item.get("description").asText()));
                cursor = root.hasNonNull("nextCursor") ? root.get("nextCursor").asText() : null;
            } while (cursor != null);
            assertThat(seen).hasSize(3).doesNotHaveDuplicates();
        }
    }

    @Test
    void dateSortWalksForwardToo() throws Exception {
        putTx(UuidV7.random(), "2026-10-01", makanan, "satu", 1000);
        putTx(UuidV7.random(), "2026-10-02", makanan, "dua", 2000);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode root = page("DATE_ASC", cursor, 1);
            root.get("items").forEach(item -> seen.add(item.get("description").asText()));
            cursor = root.hasNonNull("nextCursor") ? root.get("nextCursor").asText() : null;
        } while (cursor != null);
        assertThat(seen).containsExactly("satu", "dua");
    }

    @Test
    void malformedCursorShapesAreRejected() throws Exception {
        expectBadCursor(
                "DATE_DESC",
                encode("2026-10-01|2026-10-01T00:00:00Z|" + UUID.randomUUID() + "|x"));
        expectBadCursor("AMOUNT_DESC", encode("1000|" + UUID.randomUUID() + "|x"));
        expectBadCursor("CATEGORY_ASC", encode("Makanan"));
        expectBadCursor("AMOUNT_DESC", encode("bukan-angka|" + UUID.randomUUID()));
        expectBadCursor(
                "DATE_DESC", encode("bukan-tanggal|2026-10-01T00:00:00Z|" + UUID.randomUUID()));
        expectBadCursor("DATE_DESC", encode("2026-10-01|bukan-instant|" + UUID.randomUUID()));
    }

    private void expectBadCursor(String sort, String cursor) throws Exception {
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .cookie(session)
                                .param("sort", sort)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void patchUpdatesDateAndDescriptionIndividually() throws Exception {
        UUID id = UuidV7.random();
        putTx(id, "2026-10-05", makanan, "awal", 4000);

        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + id), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("date", "2026-10-06"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-06"))
                .andExpect(jsonPath("$.description").value("awal"));

        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + id), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("description", "  diubah  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("diubah"))
                .andExpect(jsonPath("$.date").value("2026-10-06"));
    }

    @Test
    void patchOnlyCategoryIdValidatesAgainstExistingType() throws Exception {
        UUID id = UuidV7.random();
        putTx(id, "2026-10-07", makanan, "pindah kategori", 3000);

        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + id), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("categoryId", minuman.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryName").value("Minuman"));
    }

    @Test
    void getServiceForForeignUserIsNotFound() {
        UUID id = UUID.randomUUID();
        transactionService.upsert(
                user.getId(),
                id,
                new UpsertTransactionRequest(
                        LocalDate.of(2026, 10, 9), TxType.EXPENSE, null, "milik sendiri", 700L));

        assertThatThrownBy(() -> transactionService.get(UUID.randomUUID(), id))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(404));
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, Cookie activeSession) throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(activeSession, csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        for (Cookie cookie : probe.getResponse().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("CSRF cookie tidak dibuat");
    }

    private Cookie login(String email, String password) throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf)
                                        .header("X-XSRF-TOKEN", csrf.getValue())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "email", email,
                                                                "password", password))))
                        .andExpect(status().isOk())
                        .andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if ("PET_SESSION".equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Session cookie tidak dibuat");
    }
}
