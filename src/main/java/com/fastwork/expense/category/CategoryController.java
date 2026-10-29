package com.fastwork.expense.category;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fastwork.expense.common.TxType;
import com.fastwork.expense.user.CurrentUserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Kategori pengeluaran & sumber pemasukan")
public class CategoryController {

    private final CurrentUserService currentUser;
    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "Daftar kategori/sumber, opsional difilter jenis & status arsip")
    public List<CategoryResponse> list(
            @RequestParam(required = false) TxType type,
            @RequestParam(required = false, defaultValue = "false") Boolean includeArchived) {
        return categoryService
                .list(currentUser.principal().getId(), type, Boolean.TRUE.equals(includeArchived))
                .stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Buat kategori/sumber baru")
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request) {
        UUID userId = currentUser.principal().getId();
        return CategoryResponse.from(
                categoryService.create(userId, request.type(), request.name()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Ubah nama dan/atau status arsip")
    public CategoryResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) UpdateCategoryRequest request) {
        UUID userId = currentUser.principal().getId();
        if (request == null) {
            return CategoryResponse.from(categoryService.update(userId, id, null, null));
        }
        return CategoryResponse.from(
                categoryService.update(userId, id, request.name(), request.archived()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus kategori (409 bila masih dipakai transaksi)")
    public void delete(@PathVariable UUID id) {
        categoryService.delete(currentUser.principal().getId(), id);
    }
}
