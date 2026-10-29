package com.fastwork.expense.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import com.fastwork.expense.WebFlowTestBase;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.common.UuidV7;
import com.fastwork.expense.transaction.TransactionService;
import com.fastwork.expense.transaction.UpsertTransactionRequest;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;

/** Kasus tepi kategori: 409 hapus-dipakai, kategori diarsipkan, body PATCH kosong, label income. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryEdgeFlowTest extends WebFlowTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;

    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        session = login("pemilik@example.com");
    }

    @Test
    void deleteCategoryStillUsedByTransactionConflicts() throws Exception {
        Category category = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        transactionService.upsert(
                user.getId(),
                UUID.randomUUID(),
                new UpsertTransactionRequest(
                        LocalDate.of(2026, 10, 10),
                        TxType.EXPENSE,
                        category.getId(),
                        "nasi goreng",
                        25_000L));

        mockMvc.perform(authorized(delete("/api/v1/categories/" + category.getId()), session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Kategori sedang dipakai, gunakan arsip"));
    }

    @Test
    void archivedCategoryCannotReceiveNewTransaction() throws Exception {
        Category category = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        categoryService.update(user.getId(), category.getId(), null, Boolean.TRUE);

        mockMvc.perform(
                        authorized(put("/api/v1/transactions/" + UuidV7.random()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "date",
                                                        "2026-10-10",
                                                        "type",
                                                        "EXPENSE",
                                                        "categoryId",
                                                        category.getId().toString(),
                                                        "description",
                                                        "makan",
                                                        "amount",
                                                        15000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Kategori sudah diarsipkan"));
    }

    @Test
    void patchCategoryWithoutBodyIsNoOp() throws Exception {
        Category category = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        mockMvc.perform(authorized(patch("/api/v1/categories/" + category.getId()), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Makanan"))
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void renameToItsOwnCurrentNameSucceeds() throws Exception {
        Category category = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        mockMvc.perform(
                        authorized(patch("/api/v1/categories/" + category.getId()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", " Makanan "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(" Makanan "));
    }

    @Test
    void duplicateIncomeSourceUsesIncomeLabel() throws Exception {
        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "type", "INCOME",
                                                        "name", "Gaji"))))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        authorized(post("/api/v1/categories"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "type", "INCOME",
                                                        "name", "gaji"))))
                .andExpect(status().isConflict())
                .andExpect(
                        result ->
                                assertThat(
                                                result.getResponse()
                                                        .getContentAsString()
                                                        .contains("Sumber pemasukan"))
                                        .isTrue());
    }
}
