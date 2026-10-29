package com.fastwork.expense.config;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.common.ApiError;
import com.fastwork.expense.user.PetUserDetailsService;

@Configuration
@EnableMethodSecurity
@ConfigurationProperties(prefix = "app.security")
public class SecurityConfig {

        static final String CSP = "default-src 'self'; "
                        + "base-uri 'self'; "
                        + "object-src 'none'; "
                        + "frame-ancestors 'none'; "
                        + "img-src 'self' data: blob:; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self'; "
                        + "connect-src 'self'; "
                        + "form-action 'self'";

        private boolean hstsEnabled = true;

        public void setHstsEnabled(boolean hstsEnabled) {
                this.hstsEnabled = hstsEnabled;
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        }

        @Bean
        public AuthenticationManager authenticationManager(
                        PetUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
                DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
                provider.setPasswordEncoder(passwordEncoder);
                provider.setHideUserNotFoundExceptions(true);
                return new ProviderManager(provider);
        }

        @Bean
        public SecurityContextRepository securityContextRepository() {
                return new HttpSessionSecurityContextRepository();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();

                configuration.setAllowedOrigins(
                                List.of(
                                                "https://personal-expense-4zg.pages.dev",
                                                "http://localhost:3000"));

                configuration.setAllowedMethods(
                                List.of(
                                                "GET",
                                                "POST",
                                                "PUT",
                                                "PATCH",
                                                "DELETE",
                                                "OPTIONS"));

                configuration.setAllowedHeaders(
                                List.of(
                                                "Content-Type",
                                                "X-XSRF-TOKEN",
                                                "X-CSRF-TOKEN",
                                                "Accept"));

                configuration.setAllowCredentials(true);
                configuration.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

                source.registerCorsConfiguration("/**", configuration);

                return source;
        }

        @Bean
        public SecurityFilterChain filterChain(
                        HttpSecurity http,
                        SecurityContextRepository securityContextRepository,
                        ObjectMapper objectMapper)
                        throws Exception {

                CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                csrfTokenRepository.setCookieCustomizer(cookie -> {
                        cookie.path("/");
                        cookie.sameSite("Lax");
                        cookie.secure(true); // Wajib untuk HTTPS (Production)
                });

                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                                .sessionManagement(
                                                sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                                                .sessionFixation(
                                                                                fixation -> fixation.changeSessionId()))
                                .requestCache(cache -> cache.disable())
                                .formLogin(form -> form.disable())
                                .httpBasic(basic -> basic.disable())
                                .logout(logout -> logout.disable())
                                .csrf(
                                                csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                                                                .csrfTokenRequestHandler(
                                                                                new SpaCsrfTokenRequestHandler()))
                                .addFilterAfter(
                                                new CsrfCookieFilter(),
                                                org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                                .addFilterAfter(
                                                new com.fastwork.expense.observability.UserContextMdcFilter(),
                                                org.springframework.security.web.context.SecurityContextHolderFilter.class)
                                .headers(
                                                headers -> headers
                                                                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                                                                .frameOptions(frame -> frame.deny())
                                                                .referrerPolicy(
                                                                                referrer -> referrer.policy(
                                                                                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                                                                .httpStrictTransportSecurity(
                                                                                hsts -> {
                                                                                        hsts.includeSubDomains(true)
                                                                                                        .maxAgeInSeconds(
                                                                                                                        31536000);
                                                                                        if (!hstsEnabled) {
                                                                                                hsts.disable();
                                                                                        }
                                                                                }))
                                .authorizeHttpRequests(
                                                auth -> auth.requestMatchers(
                                                                "/api/v1/health",
                                                                "/api/v1/auth/login",
                                                                "/api/v1/auth/reset-password",
                                                                "/actuator/health",
                                                                "/actuator/health/**",
                                                                "/v3/api-docs",
                                                                "/v3/api-docs/**",
                                                                "/swagger-ui.html",
                                                                "/swagger-ui/**")
                                                                .permitAll()
                                                                .anyRequest()
                                                                .authenticated())
                                .exceptionHandling(
                                                errors -> errors.authenticationEntryPoint(
                                                                (request, response, ex) -> writeError(
                                                                                response,
                                                                                objectMapper,
                                                                                HttpStatus.UNAUTHORIZED,
                                                                                "UNAUTHORIZED"))
                                                                .accessDeniedHandler(
                                                                                (request, response, ex) -> writeError(
                                                                                                response,
                                                                                                objectMapper,
                                                                                                HttpStatus.FORBIDDEN,
                                                                                                "FORBIDDEN")));

                return http.build();
        }

        private static void writeError(
                        HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String code)
                        throws IOException {
                response.setStatus(status.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(
                                response.getOutputStream(),
                                ApiError.of(
                                                code,
                                                HttpStatus.UNAUTHORIZED.equals(status)
                                                                ? "Tidak terautentikasi"
                                                                : "Akses ditolak"));
        }

        private static final class CsrfCookieFilter extends OncePerRequestFilter {
                @Override
                protected void doFilterInternal(
                                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                                throws ServletException, IOException {
                        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                        if (csrfToken != null) {
                                csrfToken.getToken();
                        }
                        filterChain.doFilter(request, response);
                }
        }
}
