package com.fastwork.expense.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fastwork.expense.common.ApiException;

/** Unit test murni untuk kelas-kelas paket user: util statis, profil, dan principal. */
class UserUnitTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void normalizeEmailRejectsBlankAndLowercases() {
        assertThat(UserService.normalizeEmail("  Campur@Contoh.COM "))
                .isEqualTo("campur@contoh.com");
        assertThatThrownBy(() -> UserService.normalizeEmail(null))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_EMAIL"));
        assertThatThrownBy(() -> UserService.normalizeEmail("   "))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_EMAIL"));
    }

    @Test
    void validatePasswordRejectsNullAndShort() {
        UserService.validatePassword("dua-belas-kar");
        assertThatThrownBy(() -> UserService.validatePassword(null))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("WEAK_PASSWORD"));
        assertThatThrownBy(() -> UserService.validatePassword("pendek"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("WEAK_PASSWORD"));
    }

    @Test
    void validateTimezoneRejectsUnknownZone() {
        UserService.validateTimezone("Asia/Jakarta");
        assertThatThrownBy(() -> UserService.validateTimezone(null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_TIMEZONE"));
        assertThatThrownBy(() -> UserService.validateTimezone(" "))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_TIMEZONE"));
        assertThatThrownBy(() -> UserService.validateTimezone("Bukan/Zona"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_TIMEZONE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateProfileBranchesApplyOnlyNonNullFields() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        UserService service = new UserService(repository, mock(PasswordEncoder.class));
        User user = new User(UUID.randomUUID(), "a@b.c", "hash", UserRole.OWNER);

        service.updateProfile(user, null, null, null);
        assertThat(user.getTimezone()).isEqualTo("Asia/Jakarta");
        assertThat(user.getDailyLimit()).isNull();
        assertThat(user.getDisplayName()).isNull();

        service.updateProfile(user, "Asia/Makassar", 50_000L, "  Nama  ");
        assertThat(user.getTimezone()).isEqualTo("Asia/Makassar");
        assertThat(user.getDailyLimit()).isEqualTo(50_000L);
        assertThat(user.getDisplayName()).isEqualTo("Nama");

        service.updateProfile(user, null, null, "   ");
        assertThat(user.getDisplayName()).isNull();
    }

    @Test
    void setPasswordStoresEncodedHash() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("baru-dua-belas-x")).thenReturn("HASH-BARU");
        UserService service = new UserService(repository, encoder);
        User user = new User(UUID.randomUUID(), "a@b.c", "lama", UserRole.OWNER);

        service.setPassword(user, "baru-dua-belas-x");

        assertThat(user.getPasswordHash()).isEqualTo("HASH-BARU");
        verify(repository).saveAndFlush(user);
    }

    @Test
    void userEntityAccessorsAndIsActive() {
        UUID id = UUID.randomUUID();
        User user = new User(id, "a@b.c", "hash", UserRole.OWNER);
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getEmail()).isEqualTo("a@b.c");
        assertThat(user.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(user.isActive()).isTrue();

        user.setDisplayName("Nama");
        assertThat(user.getDisplayName()).isEqualTo("Nama");
        user.setDisabledAt(Instant.ofEpochSecond(100));
        assertThat(user.getDisabledAt()).isEqualTo(Instant.ofEpochSecond(100));
        assertThat(user.isActive()).isFalse();
        user.setDisabledAt(null);
        assertThat(user.isActive()).isTrue();
        assertThat(user.getCreatedAt()).isNull();
        assertThat(user.getUpdatedAt()).isNull();
    }

    @Test
    void petPrincipalExposesRoleAndDisabledState() {
        User user = new User(UUID.randomUUID(), "a@b.c", "hash", UserRole.OWNER);
        PetPrincipal principal = new PetPrincipal(user);
        assertThat(principal.getId()).isEqualTo(user.getId());
        assertThat(principal.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(principal.getUsername()).isEqualTo("a@b.c");
        assertThat(principal.getPassword()).isEqualTo("hash");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
        assertThat(principal.getAuthorities()).hasSize(1);

        user.setDisabledAt(Instant.now());
        PetPrincipal disabled = new PetPrincipal(user);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.isAccountNonLocked()).isFalse();
    }

    @Test
    void currentUserServiceRejectsMissingOrForeignPrincipal() {
        UserRepository repository = mock(UserRepository.class);
        CurrentUserService service = new CurrentUserService(repository);

        assertThatThrownBy(service::principal)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("UNAUTHORIZED"));

        Authentication anonymous = new UsernamePasswordAuthenticationToken("anonymous", null);
        setAuthentication(anonymous);
        assertThatThrownBy(service::principal)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("Tidak terautentikasi"));

        User user = new User(UUID.randomUUID(), "a@b.c", "hash", UserRole.OWNER);
        setAuthentication(new UsernamePasswordAuthenticationToken(new PetPrincipal(user), null));
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(service::get)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("Sesi tidak valid"));

        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        assertThat(service.get()).isSameAs(user);
    }

    @Test
    void petUserDetailsServiceTrimsAndThrows() {
        UserRepository repository = mock(UserRepository.class);
        PetUserDetailsService service = new PetUserDetailsService(repository);

        when(repository.findByEmail(null)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class);

        User user = new User(UUID.randomUUID(), "a@b.c", "hash", UserRole.OWNER);
        when(repository.findByEmail("a@b.c")).thenReturn(Optional.of(user));
        assertThat(service.loadUserByUsername("  a@b.c  ").getId()).isEqualTo(user.getId());

        UUID other = UUID.randomUUID();
        when(repository.findById(other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.userById(other))
                .isInstanceOf(UsernameNotFoundException.class);
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        assertThat(service.userById(user.getId())).isSameAs(user);
    }

    private static void setAuthentication(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
