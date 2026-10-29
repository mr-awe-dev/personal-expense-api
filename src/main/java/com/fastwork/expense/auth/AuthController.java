package com.fastwork.expense.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fastwork.expense.user.ChangePasswordRequest;
import com.fastwork.expense.user.CurrentUserService;
import com.fastwork.expense.user.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login, logout, ganti/reset password")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final CurrentUserService currentUser;
    private final PasswordResetService passwordResetService;

    @PostMapping("/login")
    @Operation(summary = "Masuk dengan email + password")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
    }

    @PostMapping("/logout")
    @Operation(summary = "Akhiri sesi pada perangkat ini")
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.logout(httpRequest, httpResponse);
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ganti password milik pengguna yang sedang masuk")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(
                currentUser.get(), request.currentPassword(), request.newPassword());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ganti password dengan kode pemulihan (diterbitkan CLI issue-reset-code)")
    public void resetPassword(
            @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        passwordResetService.consume(request, httpRequest.getRemoteAddr());
    }
}
