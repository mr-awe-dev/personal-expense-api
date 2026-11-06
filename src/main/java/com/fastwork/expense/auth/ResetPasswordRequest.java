package com.fastwork.expense.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ResetPasswordRequest")
public record ResetPasswordRequest(
        @NotBlank @Size(max = 254) @Schema(example = "saya@example.com") String email,
        @NotBlank @Size(max = 64) @Schema(example = "12345678") String code,
        @NotBlank @Size(min = 12, max = 200) String newPassword) {}
