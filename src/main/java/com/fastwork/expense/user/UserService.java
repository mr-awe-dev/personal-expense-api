package com.fastwork.expense.user;

import java.time.ZoneId;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.UuidV7;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    public static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User createUser(String email, String rawPassword) {
        String normalized = normalizeEmail(email);
        validatePassword(rawPassword);
        if (userRepository.findByEmail(normalized).isPresent()) {
            throw ApiException.conflict("Email sudah terdaftar");
        }
        return userRepository.save(
                new User(
                        UuidV7.random(),
                        normalized,
                        passwordEncoder.encode(rawPassword),
                        UserRole.OWNER));
    }

    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CREDENTIALS", "Password lama salah");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.saveAndFlush(user);
    }

    @Transactional
    public void setPassword(User user, String newPassword) {
        validatePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.saveAndFlush(user);
    }

    @Transactional
    public User updateProfile(User user, String timezone, Long dailyLimit, String displayName) {
        if (timezone != null) {
            validateTimezone(timezone);
            user.setTimezone(timezone);
        }
        if (dailyLimit != null) {
            user.setDailyLimit(dailyLimit);
        }
        if (displayName != null) {
            user.setDisplayName(displayName.isBlank() ? null : displayName.trim());
        }
        return userRepository.saveAndFlush(user);
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("INVALID_EMAIL", "Email wajib diisi");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    "WEAK_PASSWORD", "Password minimal " + MIN_PASSWORD_LENGTH + " karakter");
        }
    }

    public static void validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "Zona waktu wajib diisi");
        }
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest(
                    "INVALID_TIMEZONE", "Zona waktu tidak dikenal: " + timezone);
        }
    }
}
