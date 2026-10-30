package com.fastwork.expense.cli;

import java.io.Console;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fastwork.expense.auth.PasswordResetService;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Profile("cli")
@Order(0)
class CliCommandRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CliCommandRunner.class);

    private static final String ENV_PASSWORD = "PET_INITIAL_PASSWORD";

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordResetService passwordResetService;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        List<String> argv = commandArgs(args.getSourceArgs());
        if (argv.isEmpty()) {
            usage();
            exit(1);
            return;
        }
        try {
            switch (argv.get(0)) {
                case "create-user" -> createUser(argv.subList(1, argv.size()));
                case "reset-password" -> resetPassword(argv.subList(1, argv.size()));
                case "issue-reset-code" -> issueResetCode(argv.subList(1, argv.size()));
                case "list-users" -> listUsers();
                default -> {
                    log.error("Perintah tidak dikenal: {}", argv.get(0));
                    usage();
                    exit(1);
                }
            }
        } catch (RuntimeException ex) {
            log.error("Gagal: {}", ex.getMessage());
            exit(1);
        }
    }

    private static List<String> commandArgs(String[] sourceArgs) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < sourceArgs.length; i++) {
            if (sourceArgs[i].endsWith(".jar")) {
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < sourceArgs.length; i++) {
            String arg = sourceArgs[i];
            if (arg.startsWith("--") && arg.contains("=")) continue;
            result.add(arg);
        }
        return result;
    }

    private void createUser(List<String> argv) {
        if (argv.isEmpty()) {
            log.error("Usage: create-user <email> [--password <password>]");
            exit(1);
            return;
        }
        User user = userService.createUser(argv.get(0), readPassword(argv));
        log.info("Pengguna dibuat: id={} email={}", user.getId(), user.getEmail());
        exit(0);
    }

    private void resetPassword(List<String> argv) {
        if (argv.isEmpty()) {
            log.error("Usage: reset-password <email> [--password <password>]");
            exit(1);
            return;
        }
        String email = UserService.normalizeEmail(argv.get(0));
        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Pengguna tidak ditemukan: " + email));
        userService.setPassword(user, readPassword(argv));
        log.info("Password diperbarui untuk: {}", user.getEmail());
        exit(0);
    }

    private void issueResetCode(List<String> argv) {
        if (argv.isEmpty()) {
            log.error("Usage: issue-reset-code <email>");
            exit(1);
            return;
        }
        String email = UserService.normalizeEmail(argv.get(0));
        String code = passwordResetService.issue(email);
        log.info(
                "Kode reset untuk {}: {} (berlaku {} menit, satu-kali)",
                email,
                code,
                PasswordResetService.CODE_TTL.toMinutes());
        exit(0);
    }

    private void listUsers() {
        userRepository
                .findAll()
                .forEach(
                        u ->
                                log.info(
                                        "id={} email={} role={}",
                                        u.getId(),
                                        u.getEmail(),
                                        u.getRole()));
        exit(0);
    }

    private String readPassword(List<String> argv) {
        for (int i = 1; i < argv.size() - 1; i++) {
            if ("--password".equals(argv.get(i))) {
                return argv.get(i + 1);
            }
        }
        String fromEnv = passwordFromEnvironment();
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        char[] typed = readConsolePassword("Password (minimal 12 karakter): ");
        if (typed != null) {
            return new String(typed);
        }
        throw new IllegalArgumentException(
                "Password belum diberikan. Gunakan --password <password> atau env "
                        + ENV_PASSWORD
                        + ".");
    }

    /** Sejam testabilitas: sumber password non-interaktif (dapat dioverride di tes). */
    String passwordFromEnvironment() {
        return System.getenv(ENV_PASSWORD);
    }

    /** Sejam testabilitas: baca password dari console interaktif bila ada. */
    char[] readConsolePassword(String prompt) {
        Console console = System.console();
        return console == null ? null : console.readPassword(prompt);
    }

    private static void usage() {
        log.info(
                String.join(
                        System.lineSeparator(),
                        "Perintah CLI:",
                        "  create-user <email> [--password <password>]",
                        "  reset-password <email> [--password <password>]",
                        "  issue-reset-code <email>",
                        "  list-users"));
    }

    /**
     * Tutup konteks lalu akhiri proses; {@link #terminate} adalah seam agar tes tidak mematikan
     * JVM.
     */
    void exit(int code) {
        SpringApplication.exit(context, () -> code);
        terminate(code);
    }

    void terminate(int code) {
        System.exit(code);
    }
}
