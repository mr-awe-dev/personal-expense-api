package com.fastwork.expense.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.fastwork.expense.common.ApiException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public PetPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof PetPrincipal p)) {
            throw ApiException.unauthorized("Tidak terautentikasi");
        }
        return p;
    }

    public User get() {
        return userRepository
                .findById(principal().getId())
                .orElseThrow(
                        () -> ApiException.unauthorized("Sesi tidak valid, silakan masuk lagi"));
    }
}
