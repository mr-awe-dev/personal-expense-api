package com.fastwork.expense.passkey;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fastwork.expense.auth.LoginResponse;
import com.fastwork.expense.user.CurrentUserService;
import com.fastwork.expense.user.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Endpoint passkey. JSON ops/hasil diteruskan apa adanya dari/ke RelyingParty
 * Yubico (format standar {publicKey: ...}) supaya bidang biner utuh; pasangan
 * challenge disimpan di sesi sisi server untuk koreksi saat verifikasi.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/passkey")
@Tag(name = "Passkey", description = "Login passkey (fingerprint/Face ID) dan kelola kredensialnya")
public class PasskeyController {

    private final PasskeyService passkeyService;
    private final CurrentUserService currentUser;

    @PostMapping(value = "/register/options", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Mulai pendaftaran passkey baru (opsi navigator.credentials.create)")
    public String registerOptions(HttpServletRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        User user = currentUser.get();
        return passkeyService.createRegistrationOptions(user, request);
    }

    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Selesaikan pendaftaran passkey (body = hasil navigator.credentials.create)")
    public PasskeyDto register(@RequestBody String body, HttpServletRequest request) {
        User user = currentUser.get();
        return passkeyService.completeRegistration(user, body, request);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Daftar passkey milik pengguna")
    public List<PasskeyDto> list() {
        return passkeyService.list(currentUser.get().getId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus satu passkey")
    public void delete(@PathVariable UUID id) {
        passkeyService.delete(currentUser.get().getId(), id);
    }

    @PostMapping(value = "/login/options", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Mulai login passkey sekali-tap (opsi navigator.credentials.get)")
    public String loginOptions(HttpServletRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return passkeyService.createLoginOptions(request);
    }

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Verifikasi assertion passkey dan buat sesi login")
    public LoginResponse login(
            @RequestBody String body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return passkeyService.login(body, request, response);
    }
}
