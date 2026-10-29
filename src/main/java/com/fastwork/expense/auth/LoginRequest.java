package com.fastwork.expense.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LoginRequest")
public record LoginRequest(
        @NotBlank @Size(max = 254) @Schema(example = "saya@example.com") String email,
        @NotBlank @Size(max = 200) String password) {}
