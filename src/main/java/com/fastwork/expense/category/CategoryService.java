package com.fastwork.expense.category;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.common.UuidV7;
import com.fastwork.expense.transaction.TransactionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public List<Category> list(UUID userId, TxType type, boolean includeArchived) {
        List<Category> all = categoryRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
        return all.stream()
                .filter(category -> type == null || category.getType() == type)
                .filter(category -> includeArchived || !Boolean.TRUE.equals(category.getArchived()))
                .toList();
    }

    @Transactional
    public Category create(UUID userId, TxType type, String name) {
        String key = nameKey(name);
        categoryRepository
                .findByUserIdAndTypeAndNameKey(userId, type, key)
                .ifPresent(
                        unused -> {
                            throw ApiException.conflict(label(type) + " \"" + key + "\" sudah ada");
                        });
        return categoryRepository.save(new Category(UuidV7.random(), userId, type, name, key));
    }

    @Transactional
    public Category update(UUID userId, UUID id, String name, Boolean archived) {
        Category category = requireOwn(userId, id);
        if (name != null) {
            String key = nameKey(name);
            TxType type = category.getType();
            categoryRepository
                    .findByUserIdAndTypeAndNameKey(userId, type, key)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(
                            other -> {
                                throw ApiException.conflict(
                                        label(type) + " \"" + key + "\" sudah ada");
                            });
            category.rename(name, key);
        }
        if (archived != null) {
            category.setArchived(archived);
        }
        return categoryRepository.saveAndFlush(category);
    }

    @Transactional(readOnly = true)
    public Category requireUsable(UUID userId, UUID categoryId, TxType expectedType) {
        Category category =
                categoryRepository
                        .findByIdAndUserId(categoryId, userId)
                        .orElseThrow(() -> ApiException.notFound("Kategori tidak ditemukan"));
        if (Boolean.TRUE.equals(category.getArchived())) {
            throw ApiException.unprocessable("Kategori sudah diarsipkan");
        }
        if (category.getType() != expectedType) {
            throw ApiException.unprocessable("Jenis kategori tidak cocok dengan jenis transaksi");
        }
        return category;
    }

    @Transactional(readOnly = true)
    public String nameOf(UUID userId, UUID categoryId) {
        return categoryRepository
                .findByIdAndUserId(categoryId, userId)
                .map(Category::getName)
                .orElse(null);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Category category = requireOwn(userId, id);
        if (transactionRepository.existsByCategoryIdAndUserId(id, userId)) {
            throw ApiException.conflict(
                    label(category.getType()) + " sedang dipakai, gunakan arsip");
        }
        categoryRepository.deleteById(id);
    }

    private Category requireOwn(UUID userId, UUID id) {
        return categoryRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Kategori tidak ditemukan"));
    }

    private static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String label(TxType type) {
        return type.isIncome() ? "Sumber pemasukan" : "Kategori";
    }
}
