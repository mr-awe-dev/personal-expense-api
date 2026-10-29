package com.fastwork.expense.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    Optional<PasswordReset> findByUserIdAndTokenHashAndUsedAtIsNullAndExpiresAtAfter(
            UUID userId, String tokenHash, Instant now);

    void deleteByUserId(UUID userId);

    void deleteByUserIdAndIdNot(UUID userId, UUID id);
}
