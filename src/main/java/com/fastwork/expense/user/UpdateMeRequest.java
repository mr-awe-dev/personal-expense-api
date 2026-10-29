package com.fastwork.expense.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpdateMeRequest")
public record UpdateMeRequest(
        @Schema(example = "Asia/Jakarta")
                @Size(max = 64)
                @Pattern(
                        regexp = "^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*$",
                        message = "harus berupa id zona waktu IANA, contoh Asia/Jakarta")
                String timezone,
        @Schema(example = "50000") @Min(0) @Max(999999999999L) Long dailyLimit,
        @Size(max = 80) String displayName) {}
