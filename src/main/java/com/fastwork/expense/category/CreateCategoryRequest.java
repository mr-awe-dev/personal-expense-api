package com.fastwork.expense.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fastwork.expense.common.TxType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CreateCategoryRequest")
public record CreateCategoryRequest(@NotNull TxType type, @NotBlank @Size(max = 50) String name) {}
