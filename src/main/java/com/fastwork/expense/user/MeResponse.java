package com.fastwork.expense.user;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Me")
public record MeResponse(
        UUID id,
        String email,
        String displayName,
        @Schema(example = "Asia/Jakarta") String timezone,
        Long dailyLimit,
        UserRole role,
        Instant createdAt) {

    public static MeResponse from(User user) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getTimezone(),
                user.getDailyLimit(),
                user.getRole(),
                user.getCreatedAt());
    }
}
