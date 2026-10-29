package com.fastwork.expense.budget;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fastwork.expense.user.CurrentUserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/budgets")
@Tag(name = "Budgets", description = "Budget per kategori pengeluaran (daily/monthly)")
public class BudgetController {

    private final CurrentUserService currentUser;
    private final BudgetService budgetService;

    @GetMapping
    @Operation(summary = "Daftar budget milik user (ikut kategorinya, termasuk yang diarsipkan)")
    public List<BudgetResponse> list() {
        return budgetService.list(currentUser.principal().getId());
    }

    @PutMapping("/{categoryId}")
    @Operation(summary = "Upsert budget kategori (1 kategori = 1 budget)")
    public BudgetResponse upsert(
            @PathVariable UUID categoryId, @Valid @RequestBody UpsertBudgetRequest request) {
        return budgetService.upsert(currentUser.principal().getId(), categoryId, request);
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus budget kategori")
    public void delete(@PathVariable UUID categoryId) {
        budgetService.delete(currentUser.principal().getId(), categoryId);
    }
}
