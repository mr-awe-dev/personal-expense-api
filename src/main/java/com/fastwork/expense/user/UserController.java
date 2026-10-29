package com.fastwork.expense.user;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me")
@Tag(name = "User", description = "Profil pengguna yang sedang masuk")
public class UserController {

    private final CurrentUserService currentUser;
    private final UserService userService;

    @GetMapping
    public MeResponse me() {
        return MeResponse.from(currentUser.get());
    }

    @PatchMapping
    public MeResponse updateMe(@Valid @RequestBody(required = false) UpdateMeRequest request) {
        User user = currentUser.get();
        if (request == null) {
            return MeResponse.from(user);
        }
        User updated =
                userService.updateProfile(
                        user, request.timezone(), request.dailyLimit(), request.displayName());
        return MeResponse.from(updated);
    }
}
