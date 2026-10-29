package com.fastwork.expense.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.user.MeResponse;
import com.fastwork.expense.user.PetPrincipal;
import com.fastwork.expense.user.PetUserDetailsService;
import com.fastwork.expense.user.User;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final PetUserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final LoginRateLimiter rateLimiter;

    @Transactional
    public LoginResponse login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String email = request.email() == null ? "" : request.email().trim();
        rateLimiter.assertAttemptAllowed(httpRequest.getRemoteAddr(), email);
        Authentication authentication;
        try {
            authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    email, request.password()));
        } catch (DisabledException ex) {
            rateLimiter.recordFailure(email);
            throw ApiException.unauthorized("Akun dinonaktifkan");
        } catch (AuthenticationException ex) {
            rateLimiter.recordFailure(email);
            throw ApiException.unauthorized("Email atau password salah");
        }
        rateLimiter.recordSuccess(email);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        httpRequest.getSession(true);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        PetPrincipal principal = (PetPrincipal) authentication.getPrincipal();
        User user = userDetailsService.userById(principal.getId());
        return new LoginResponse(MeResponse.from(user));
    }

    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, authentication);
        SecurityContextHolder.clearContext();
    }
}
