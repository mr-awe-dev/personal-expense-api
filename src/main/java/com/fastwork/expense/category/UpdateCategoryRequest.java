package com.fastwork.expense.category;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpdateCategoryRequest")
public record UpdateCategoryRequest(@Size(max = 50) String name, Boolean archived) {}
