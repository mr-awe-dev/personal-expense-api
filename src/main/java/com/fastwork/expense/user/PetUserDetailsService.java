package com.fastwork.expense.user;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PetUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public PetPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository
                .findByEmail(email == null ? null : email.trim())
                .map(PetPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Email atau password salah"));
    }

    @Transactional(readOnly = true)
    public User userById(java.util.UUID id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("Pengguna tidak ditemukan"));
    }
}
