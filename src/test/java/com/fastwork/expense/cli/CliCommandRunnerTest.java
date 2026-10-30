package com.fastwork.expense.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import com.fastwork.expense.auth.PasswordResetService;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserRole;
import com.fastwork.expense.user.UserService;

/**
 * Tes unit CliCommandRunner tanpa mematikan JVM: subclass menggantikan seam {@code terminate},
 * {@code passwordFromEnvironment}, dan {@code readConsolePassword}.
 */
class CliCommandRunnerTest {

    private static final String PASSWORD = "rahasia-dua-belas";

    private final UserService userService = mock(UserService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordResetService passwordResetService = mock(PasswordResetService.class);
    private final ConfigurableApplicationContext context =
            mock(ConfigurableApplicationContext.class);

    private final List<Integer> exits = new ArrayList<>();
    private String envPassword;
    private char[] consolePassword;

    private CliCommandRunner runner() {
        return new CliCommandRunner(userService, userRepository, passwordResetService, context) {
            @Override
            void terminate(int code) {
                exits.add(code);
            }

            @Override
            String passwordFromEnvironment() {
                return envPassword;
            }

            @Override
            char[] readConsolePassword(String prompt) {
                return consolePassword;
            }
        };
    }

    private void run(String... argv) {
        ApplicationArguments args = mock(ApplicationArguments.class);
        when(args.getSourceArgs()).thenReturn(argv);
        runner().run(args);
    }

    @Test
    void noArgumentsShowsUsageAndExitsWithFailure() {
        run();
        assertThat(exits).containsExactly(1);
    }

    @Test
    void unknownCommandExitsWithFailure() {
        run("perintah-asal");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void jarPrefixAndPropertyFlagsAreFilteredOut() {
        User created = new User(UUID.randomUUID(), "a@b.c", "hash", UserRole.OWNER);
        when(userRepository.findByEmail("a@b.c")).thenReturn(Optional.of(created));

        run(
                "app.jar",
                "--spring.profiles.active=cli",
                "reset-password",
                "a@b.c",
                "--password",
                PASSWORD);

        verify(userService).setPassword(created, PASSWORD);
        assertThat(exits).containsExactly(0);
    }

    @Test
    void flagWithoutEqualsIsTreatedAsCommandAndRejected() {
        run("--tanpa-sama-dengan");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void createUserHappyPathUsesFlagPassword() {
        User created = new User(UUID.randomUUID(), "baru@contoh.com", "hash", UserRole.OWNER);
        when(userService.createUser("baru@contoh.com", PASSWORD)).thenReturn(created);

        run("create-user", "baru@contoh.com", "--password", PASSWORD);

        verify(userService).createUser("baru@contoh.com", PASSWORD);
        assertThat(exits).containsExactly(0);
    }

    @Test
    void flagScanSkipsPositionalBeforePassword() {
        User created = new User(UUID.randomUUID(), "a@b.c", "hash", UserRole.OWNER);
        when(userService.createUser("a@b.c", PASSWORD)).thenReturn(created);

        run("create-user", "a@b.c", "sisa-arg", "--password", PASSWORD);

        verify(userService).createUser("a@b.c", PASSWORD);
        assertThat(exits).containsExactly(0);
    }

    @Test
    void createUserWithoutEmailExits() {
        run("create-user");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void createUserServiceFailureIsReported() {
        when(userService.createUser(anyString(), anyString()))
                .thenThrow(ApiException.conflict("Email sudah terdaftar"));

        run("create-user", "ada@contoh.com", "--password", PASSWORD);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void createUserFallsBackToEnvironmentPassword() {
        envPassword = "dari-env-123456";
        User created = new User(UUID.randomUUID(), "env@contoh.com", "hash", UserRole.OWNER);
        when(userService.createUser("env@contoh.com", envPassword)).thenReturn(created);
        run("create-user", "env@contoh.com");
        verify(userService).createUser(eq("env@contoh.com"), eq("dari-env-123456"));
        assertThat(exits).containsExactly(0);
    }

    @Test
    void blankEnvironmentPasswordFallsThroughToConsole() {
        envPassword = "   ";
        consolePassword = "dari-console-12".toCharArray();
        User created = new User(UUID.randomUUID(), "console@contoh.com", "hash", UserRole.OWNER);
        when(userService.createUser("console@contoh.com", "dari-console-12")).thenReturn(created);
        run("create-user", "console@contoh.com");
        verify(userService).createUser(eq("console@contoh.com"), eq("dari-console-12"));
        assertThat(exits).containsExactly(0);
    }

    @Test
    void noPasswordSourceAtAllFails() {
        envPassword = null;
        consolePassword = null;
        run("create-user", "sumber@contoh.com");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void passwordFlagAtLastPositionIsIgnored() {
        envPassword = null;
        consolePassword = null;
        run("create-user", "sumber@contoh.com", "--password");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void resetPasswordHappyPathNormalizesEmail() {
        User existing = new User(UUID.randomUUID(), "pemilik@example.com", "hash", UserRole.OWNER);
        when(userRepository.findByEmail("pemilik@example.com")).thenReturn(Optional.of(existing));

        run("reset-password", "Pemilik@Example.com", "--password", PASSWORD);

        verify(userService).setPassword(existing, PASSWORD);
        assertThat(exits).containsExactly(0);
    }

    @Test
    void resetPasswordWithoutEmailExits() {
        run("reset-password");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void resetPasswordUnknownUserExits() {
        when(userRepository.findByEmail("hilang@contoh.com")).thenReturn(Optional.empty());
        run("reset-password", "hilang@contoh.com", "--password", PASSWORD);
        assertThat(exits).containsExactly(1);
    }

    @Test
    void issueResetCodeHappyPath() {
        when(passwordResetService.issue("lupa@contoh.com")).thenReturn("KODE1234");
        run("issue-reset-code", "lupa@contoh.com");
        verify(passwordResetService).issue("lupa@contoh.com");
        assertThat(exits).containsExactly(0);
    }

    @Test
    void issueResetCodeWithoutEmailExits() {
        run("issue-reset-code");
        assertThat(exits).containsExactly(1);
    }

    @Test
    void listUsersPrintsEveryRowAndExitsZero() {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                new User(UUID.randomUUID(), "satu@contoh.com", "h", UserRole.OWNER),
                                new User(
                                        UUID.randomUUID(), "dua@contoh.com", "h", UserRole.OWNER)));
        run("list-users");
        assertThat(exits).containsExactly(0);
    }

    @Test
    void listUsersWithEmptyTableStillExitsZero() {
        when(userRepository.findAll()).thenReturn(List.of());
        run("list-users");
        assertThat(exits).containsExactly(0);
    }

    @Test
    void realSeamMethodsDelegateToJvmEnvironment() {
        // instance TANPA override: body asli seam harus berjalan (System.getenv /
        // System.console)
        CliCommandRunner real =
                new CliCommandRunner(userService, userRepository, passwordResetService, context);
        // PET_INITIAL_PASSWORD tidak ter-set di lingkungan tes → null
        assertThat(real.passwordFromEnvironment()).isNull();
        // surefire tidak punya console interaktif → null
        assertThat(real.readConsolePassword("prompt")).isNull();
        // CATATAN: terminate() sengaja TIDAK dipanggil — body-nya System.exit dan akan
        // mematikan JVM surefire; ia hanya diuji lewat override subclass.
    }

    // =========================================================
    // COMMAND ARGUMENT PARSING
    // =========================================================

    @Test
    void jarWithoutCommandExitsWithFailure() {
        run("app.jar");

        assertThat(exits).containsExactly(1);
        verifyNoInteractions(userService, userRepository, passwordResetService);
    }

    @Test
    void jarArgumentsWithoutPropertyFlagsAreParsed() {
        User created = new User(UUID.randomUUID(), "jar@example.com", "hash", UserRole.OWNER);

        when(userService.createUser("jar@example.com", PASSWORD)).thenReturn(created);

        run("app.jar", "create-user", "jar@example.com", "--password", PASSWORD);

        verify(userService).createUser("jar@example.com", PASSWORD);
        assertThat(exits).containsExactly(0);
    }

    // =========================================================
    // CREATE USER
    // =========================================================

    @Test
    void createUserUsesConsolePasswordWhenEnvironmentIsNull() {
        envPassword = null;
        consolePassword = "console-password-123".toCharArray();

        User created = new User(UUID.randomUUID(), "console@example.com", "hash", UserRole.OWNER);

        when(userService.createUser("console@example.com", "console-password-123"))
                .thenReturn(created);

        run("create-user", "console@example.com");

        verify(userService).createUser("console@example.com", "console-password-123");

        assertThat(exits).containsExactly(0);
    }

    @Test
    void createUserFailsWhenEnvironmentAndConsolePasswordsAreUnavailable() {
        envPassword = " ";
        consolePassword = null;

        run("create-user", "console@example.com");

        assertThat(exits).containsExactly(1);
        verify(userService, org.mockito.Mockito.never()).createUser(anyString(), anyString());
    }

    // =========================================================
    // RESET PASSWORD
    // =========================================================

    @Test
    void resetPasswordServiceFailureExitsWithFailure() {
        User existing = new User(UUID.randomUUID(), "user@example.com", "hash", UserRole.OWNER);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));

        doThrow(new IllegalArgumentException("Password tidak valid"))
                .when(userService)
                .setPassword(existing, PASSWORD);

        run("reset-password", "user@example.com", "--password", PASSWORD);

        verify(userService).setPassword(existing, PASSWORD);
        assertThat(exits).containsExactly(1);
    }

    @Test
    void resetPasswordNormalizesEmailWithWhitespace() {
        User existing = new User(UUID.randomUUID(), "user@example.com", "hash", UserRole.OWNER);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));

        run("reset-password", " USER@EXAMPLE.COM ", "--password", PASSWORD);

        verify(userRepository).findByEmail("user@example.com");
        verify(userService).setPassword(existing, PASSWORD);
        assertThat(exits).containsExactly(0);
    }

    // =========================================================
    // ISSUE RESET CODE
    // =========================================================

    @Test
    void issueResetCodeNormalizesEmail() {
        when(passwordResetService.issue("user@example.com")).thenReturn("ABC123");

        run("issue-reset-code", " USER@EXAMPLE.COM ");

        verify(passwordResetService).issue("user@example.com");
        assertThat(exits).containsExactly(0);
    }

    @Test
    void issueResetCodeServiceFailureExitsWithFailure() {
        when(passwordResetService.issue("user@example.com"))
                .thenThrow(new IllegalStateException("Database unavailable"));

        run("issue-reset-code", "user@example.com");

        verify(passwordResetService).issue("user@example.com");
        assertThat(exits).containsExactly(1);
    }

    // =========================================================
    // LIST USERS
    // =========================================================

    @Test
    void listUsersRepositoryFailureExitsWithFailure() {
        when(userRepository.findAll()).thenThrow(new IllegalStateException("Database unavailable"));

        run("list-users");

        verify(userRepository).findAll();
        assertThat(exits).containsExactly(1);
    }

    // =========================================================
    // APPLICATION CONTEXT
    // =========================================================

    @Test
    void exitClosesApplicationContextAndDelegatesTermination() {
        CliCommandRunner testRunner = runner();

        testRunner.exit(7);

        verify(context).close();
        assertThat(exits).containsExactly(7);
    }
}
