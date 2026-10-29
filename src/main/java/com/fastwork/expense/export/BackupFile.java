package com.fastwork.expense.export;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BackupFile")
public record BackupFile(
        int version,
        Instant exportedAt,
        BackupProfile user,
        List<BackupCategory> categories,
        List<BackupTransaction> transactions,
        List<BackupBudget> budgets,
        List<BackupClosing> closings) {

    @Schema(name = "BackupProfile")
    public record BackupProfile(String displayName, String timezone, Long dailyLimit) {}

    @Schema(name = "BackupCategory")
    public record BackupCategory(
            UUID id,
            String type,
            String name,
            Boolean archived,
            Instant createdAt,
            Instant updatedAt) {}

    @Schema(name = "BackupTransaction")
    public record BackupTransaction(
            UUID id,
            java.time.LocalDate date,
            String type,
            UUID categoryId,
            String description,
            Long amount,
            UUID closingId,
            Instant createdAt,
            Instant updatedAt) {}

    @Schema(name = "BackupBudget")
    public record BackupBudget(
            UUID id,
            UUID categoryId,
            String period,
            Long amount,
            String description,
            Instant createdAt,
            Instant updatedAt) {}

    @Schema(name = "BackupClosing")
    public record BackupClosing(
            UUID id,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd,
            Long totalIncome,
            Long totalExpense,
            Long net,
            Integer txCount,
            String fileName,
            Instant closedAt) {}
}
