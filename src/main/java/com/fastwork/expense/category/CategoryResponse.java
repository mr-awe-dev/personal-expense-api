package com.fastwork.expense.category;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Category")
public record CategoryResponse(
        UUID id, String type, String name, boolean archived, Instant createdAt) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getType().name(),
                category.getName(),
                Boolean.TRUE.equals(category.getArchived()),
                category.getCreatedAt());
    }
}
