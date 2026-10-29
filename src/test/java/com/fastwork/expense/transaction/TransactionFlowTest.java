package com.fastwork.expense.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.category.Category;
import com.fastwork.expense.category.CategoryService;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.common.UuidV7;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";
    private static final long NO_AMOUNT = 0L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private CategoryService categoryService;

    private User user;
    private Cookie session;
    private Category makanan;
    private Category gaji;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
        makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void putCreatesExpenseWithoutCategoryAndWithCategory() throws Exception {
        mockMvc.perform(
                        authorized(put("/api/v1/transactions/" + UuidV7.random()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "date",
                                                        "2026-10-20",
                                                        "type",
                                                        "EXPENSE",
                                                        "description",
                                                        "Kopi pagi",
                                                        "amount",
                                                        18000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").doesNotExist())
                .andExpect(jsonPath("$.categoryName").doesNotExist());

        mockMvc.perform(
                        authorized(put("/api/v1/transactions/" + UuidV7.random()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "date",
                                                        "2026-10-20",
                                                        "type",
                                                        "EXPENSE",
                                                        "categoryId",
                                                        makanan.getId().toString(),
                                                        "amount",
                                                        25000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(makanan.getId().toString()))
                .andExpect(jsonPath("$.categoryName").value("Makanan"));
    }

    @Test
    void putIsIdempotentForSameId() throws Exception {
        UUID id = UuidV7.random();
        postTransaction(id, "2026-10-20", "EXPENSE", makanan.getId(), "Makan siang", 25000)
                .andExpect(status().isOk());
        putTransaction(id, "2026-10-21", "EXPENSE", makanan.getId(), "Makan siang besar", 30000)
                .andExpect(status().isOk());

        MvcResult result =
                mockMvc.perform(get("/api/v1/transactions").cookie(session).param("limit", "50"))
                        .andExpect(status().isOk())
                        .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("items").size()).isEqualTo(1);
        assertThat(body.at("/items/0/amount").asLong()).isEqualTo(30000L);
        assertThat(body.at("/items/0/description").asText()).isEqualTo("Makan siang besar");
        assertThat(body.at("/items/0/date").asText()).isEqualTo("2026-10-21");
    }

    @Test
    void incomeRequiresSource() throws Exception {
        putTransaction(UuidV7.random(), "2026-10-20", "INCOME", null, "Bonus", 500000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_ENTITY"));
    }

    @Test
    void categoryTypeMustMatchTransactionType() throws Exception {
        putTransaction(UuidV7.random(), "2026-10-20", "INCOME", makanan.getId(), "Salah tempel", 1)
                .andExpect(status().isUnprocessableEntity());
        putTransaction(UuidV7.random(), "2026-10-20", "EXPENSE", gaji.getId(), "Salah tempel", 1)
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownCategoryIsRejected() throws Exception {
        putTransaction(
                        UuidV7.random(),
                        "2026-10-20",
                        "EXPENSE",
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "Marginal",
                        1)
                .andExpect(status().isNotFound());
    }

    @Test
    void amountBoundsAreEnforced() throws Exception {
        putTransaction(UuidV7.random(), "2026-10-20", "EXPENSE", makanan.getId(), "Nol", NO_AMOUNT)
                .andExpect(status().isBadRequest());

        putTransaction(
                        UuidV7.random(),
                        "2026-10-20",
                        "EXPENSE",
                        makanan.getId(),
                        "Kelebihan",
                        1_000_000_000_000L)
                .andExpect(status().isBadRequest());

        putTransaction(
                        UuidV7.random(),
                        "2026-10-20",
                        "EXPENSE",
                        makanan.getId(),
                        "Deskripsi " + "panjang".repeat(50),
                        10_000)
                .andExpect(status().isBadRequest());
    }

    @Test
    void putForeignIdIsRejectedWithoutSideEffects() throws Exception {
        User other = userService.createUser("Lain@example.com", PASSWORD);
        Cookie otherSession = login("lain@example.com", PASSWORD);

        UUID victimId = UuidV7.random();
        putTransaction(victimId, "2026-10-20", "EXPENSE", makanan.getId(), "Milik pemilik", 1)
                .andExpect(status().isOk());

        // user lain memakai ID yang sama → harus ditolak dan tidak menimpa data pemilik
        mockMvc.perform(
                        authorized(put("/api/v1/transactions/" + victimId), otherSession)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "date",
                                                        "2026-10-20",
                                                        "type",
                                                        "EXPENSE",
                                                        "description",
                                                        "Rampok",
                                                        "amount",
                                                        999999))))
                .andExpect(status().isNotFound());

        // PATCH/DELETE milik user lain → 404
        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + victimId), otherSession)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("amount", 500)))
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        mockMvc.perform(withCsrf(delete("/api/v1/transactions/" + victimId)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(authorized(delete("/api/v1/transactions/" + victimId), otherSession))
                .andExpect(status().isNotFound());

        // patch/delete user lain saat login sebagai pemegang akun tetap bekerja miliknya sendiri
        Category makananLain = categoryService.create(other.getId(), TxType.EXPENSE, "Makanan");
        UUID ownId = UuidV7.random();
        putTransactionAs(
                        ownId,
                        "2026-10-20",
                        "EXPENSE",
                        makananLain.getId(),
                        "Milik lain",
                        5,
                        otherSession)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/transactions").cookie(otherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.expense").value(5));

        mockMvc.perform(get("/api/v1/transactions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.expense").value(1));
    }

    @Test
    void listFiltersReturnOnlyMatchingRows() throws Exception {
        putTransaction(
                        UuidV7.random(),
                        "2026-10-01",
                        "EXPENSE",
                        makanan.getId(),
                        "kopi sore",
                        15000)
                .andExpect(status().isOk());
        putTransaction(UuidV7.random(), "2026-10-02", "EXPENSE", makanan.getId(), "", 20000)
                .andExpect(status().isOk());
        putTransaction(UuidV7.random(), "2026-09-20", "INCOME", gaji.getId(), "", 1000000)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/transactions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.totals.income").value(1000000))
                .andExpect(jsonPath("$.totals.expense").value(35000))
                .andExpect(jsonPath("$.totals.net").value(965000));

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("type", "EXPENSE"))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(
                        get("/api/v1/transactions")
                                .cookie(session)
                                .param("categoryId", gaji.getId().toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totals.net").value(1000000));

        mockMvc.perform(
                        get("/api/v1/transactions")
                                .cookie(session)
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-31"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totals.expense").value(35000))
                .andExpect(jsonPath("$.totals.income").value(0));

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("q", "kopi"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("kopi sore"));

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("q", "KOP"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void listKeysetPaginationWalksAllRows() throws Exception {
        for (int i = 1; i <= 4; i++) {
            putTransaction(
                            UuidV7.random(),
                            "2026-10-0" + i,
                            "EXPENSE",
                            makanan.getId(),
                            "transaksi " + i,
                            1000 * i)
                    .andExpect(status().isOk());
        }

        MvcResult page =
                mockMvc.perform(get("/api/v1/transactions").cookie(session).param("limit", "2"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andReturn();
        var body = objectMapper.readTree(page.getResponse().getContentAsString());
        String cursor = body.get("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        assertThat(body.get("totals").get("expense").asLong()).isEqualTo(10000L);

        MvcResult second =
                mockMvc.perform(
                                get("/api/v1/transactions")
                                        .cookie(session)
                                        .param("limit", "2")
                                        .param("cursor", cursor))
                        .andExpect(status().isOk())
                        .andReturn();
        var secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("items").size()).isEqualTo(2);
        assertThat(secondBody.has("nextCursor")).isFalse();

        // gabungan dua halaman = seluruh 4 transaksi, urut tanggal desc
        var all = new java.util.ArrayList<String>();
        body.get("items").forEach(item -> all.add(item.at("/description").asText()));
        secondBody.get("items").forEach(item -> all.add(item.at("/description").asText()));
        assertThat(all).containsExactly("transaksi 4", "transaksi 3", "transaksi 2", "transaksi 1");
    }

    @Test
    void listSortsByDateAndAmount() throws Exception {
        putTransaction(UuidV7.random(), "2026-10-01", "EXPENSE", makanan.getId(), "kecil", 5)
                .andExpect(status().isOk());
        putTransaction(UuidV7.random(), "2026-10-03", "INCOME", gaji.getId(), "besar", 9000)
                .andExpect(status().isOk());
        putTransaction(UuidV7.random(), "2026-10-02", "EXPENSE", makanan.getId(), "sedang", 7000)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("sort", "DATE_ASC"))
                .andExpect(jsonPath("$.items[0].description").value("kecil"));

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("sort", "AMOUNT_DESC"))
                .andExpect(jsonPath("$.items[0].description").value("besar"));

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("sort", "AMOUNT_ASC"))
                .andExpect(jsonPath("$.items[0].description").value("kecil"));

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("sort", "CATEGORY_ASC"))
                .andExpect(jsonPath("$.items[0].categoryName").value("Gaji"));
    }

    @Test
    void patchUpdatesSingleFields() throws Exception {
        UUID id = UuidV7.random();
        putTransaction(id, "2026-10-20", "EXPENSE", makanan.getId(), "Makan malam", 40000)
                .andExpect(status().isOk());

        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + id), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("amount", 42000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(42000))
                .andExpect(jsonPath("$.description").value("Makan malam"))
                .andExpect(jsonPath("$.date").value("2026-10-20"));

        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + id), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "INCOME"))))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + id), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "type",
                                                        "INCOME",
                                                        "categoryId",
                                                        gaji.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("INCOME"))
                .andExpect(jsonPath("$.categoryName").value("Gaji"));
    }

    @Test
    void patchAndDeleteUnknownId() throws Exception {
        mockMvc.perform(
                        authorized(patch("/api/v1/transactions/" + UuidV7.random()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("amount", 9))))
                .andExpect(status().isNotFound());

        mockMvc.perform(authorized(delete("/api/v1/transactions/" + UuidV7.random()), session))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteThenUndoReputRestoresRow() throws Exception {
        UUID id = UuidV7.random();
        putTransaction(id, "2026-10-20", "EXPENSE", makanan.getId(), "Hapus tes", 33)
                .andExpect(status().isOk());

        mockMvc.perform(authorized(delete("/api/v1/transactions/" + id), session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/transactions").cookie(session))
                .andExpect(jsonPath("$.items.length()").value(0));

        putTransaction(id, "2026-10-20", "EXPENSE", makanan.getId(), "Hapus tes", 33)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/transactions").cookie(session))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(csrf).header(CSRF_HEADER, csrf.getValue());
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

    private org.springframework.test.web.servlet.ResultActions putTransaction(
            UUID id, String date, String type, UUID categoryId, String description, long amount)
            throws Exception {
        return mockMvc.perform(
                authorized(put("/api/v1/transactions/" + id), session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody(date, type, categoryId, description, amount)));
    }

    private org.springframework.test.web.servlet.ResultActions putTransactionAs(
            UUID id,
            String date,
            String type,
            UUID categoryId,
            String description,
            long amount,
            Cookie activeSession)
            throws Exception {
        return mockMvc.perform(
                authorized(put("/api/v1/transactions/" + id), activeSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody(date, type, categoryId, description, amount)));
    }

    private org.springframework.test.web.servlet.ResultActions postTransaction(
            UUID id, String date, String type, UUID categoryId, String description, long amount)
            throws Exception {
        return putTransaction(id, date, type, categoryId, description, amount);
    }

    private String transactionBody(
            String date, String type, UUID categoryId, String description, long amount)
            throws Exception {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"date\":\"").append(date).append('"');
        sb.append(",\"type\":\"").append(type).append('"');
        if (categoryId != null) {
            sb.append(",\"categoryId\":\"").append(categoryId).append('"');
        }
        sb.append(",\"description\":\"").append(description).append('"');
        sb.append(",\"amount\":").append(amount);
        sb.append('}');
        return sb.toString();
    }

    private Cookie login(String email, String password) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
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

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
