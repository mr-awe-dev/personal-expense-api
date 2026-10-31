package com.fastwork.expense.config;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.common.ApiError;
import com.fastwork.expense.user.PetUserDetailsService;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

        // 1. Best Practice: Gunakan @Value daripada @ConfigurationProperties untuk 1-2
        // properti sederhana
        // Hindari anti-pattern menggabungkan @Configuration dan
        // @ConfigurationProperties di kelas yang sama.
        @Value("${app.security.hsts-enabled:true}")
        private boolean hstsEnabled;

        // Opsional: Jika backend perlu menyebutkan URL-nya sendiri di CSP (Defense in
        // Depth). Tanpa default URL spesifik deployment — lihat D36 (fail-fast).
        @Value("${app.security.backend-url:}")
        private String backendUrl;

        // 2. Content Security Policy (CSP) yang ketat untuk API Backend
        // Catatan: CSP paling krusial diatur di sisi Frontend (Cloudflare Pages).
        // Di backend, ini berfungsi sebagai lapisan pertahanan tambahan (Defense in
        // Depth).
        static final String CSP = "default-src 'self'; "
                        + "base-uri 'self'; "
                        + "object-src 'none'; "
                        + "frame-ancestors 'none'; "
                        + "img-src 'self' data: blob:; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self'; "
                        + "form-action 'self'";

        @Bean
        public PasswordEncoder passwordEncoder() {
                // Best Practice: Argon2 adalah standar emas saat ini untuk hashing password
                return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        }

        @Bean
        public AuthenticationManager authenticationManager(
                        PetUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
                DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
                provider.setPasswordEncoder(passwordEncoder);
                provider.setHideUserNotFoundExceptions(true); // Mencegah User Enumeration Attack
                return new ProviderManager(provider);
        }

        @Bean
        public SecurityContextRepository securityContextRepository() {
                return new HttpSessionSecurityContextRepository();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();

                // 3. Best Practice CORS: Gunakan setAllowedOriginPatterns untuk mendukung
                // wildcard
                // pada subdomain project Anda sendiri. Ini memungkinkan URL Preview Cloudflare
                // Pages
                // (misal: https://abc123.personal-expense-4zg.pages.dev) untuk mengakses
                // backend
                // tanpa perlu update kode setiap kali deploy, sambil tetap memblokir domain
                // asing.
                configuration.setAllowedOriginPatterns(
                                List.of(
                                                "https://personal-expense-4zg.pages.dev", // Production FE
                                                "https://*.personal-expense-4zg.pages.dev", // Preview/Deploy FE
                                                "http://localhost:3000", // Local Dev (Next.js/Custom)
                                                "http://localhost:5173" // Local Dev (Vite default)
                                ));

                configuration.setAllowedMethods(
                                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

                configuration.setAllowedHeaders(
                                List.of(
                                                "Content-Type",
                                                "Authorization",
                                                "X-XSRF-TOKEN",
                                                "X-CSRF-TOKEN",
                                                "Accept",
                                                "Cache-Control"));

                configuration.setAllowCredentials(
                                true); // Wajib true karena kita menggunakan Cookie-based CSRF/Session
                configuration.setMaxAge(
                                3600L); // Cache preflight request selama 1 jam untuk optimalisasi performa

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

                // 4. CSRF Configuration untuk SPA
                CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                csrfTokenRepository.setCookieCustomizer(
                                cookie -> {
                                        cookie.path("/");
                                        cookie.sameSite(
                                                        "Lax"); // Lax aman untuk navigasi normal, ganti "Strict" jika
                                                                // ingin
                                        // lebih ketat
                                        cookie.secure(true); // Wajib true untuk HTTPS production
                                });

                // Bangun CSP dinamis: connect-src hanya diperluas jika backend-url diisi,
                // tanpa hardcode URL pribadi deployment.
                String finalCsp = cspDirectives(backendUrl);

                http
                                // CORS
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                                // Session & Context Management
                                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                                .sessionManagement(
                                                sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                                                .sessionFixation(
                                                                                fixation -> fixation.changeSessionId())) // Mencegah
                                // Session
                                // Fixation
                                // Attack
                                .requestCache(
                                                cache -> cache.disable()) // Penting untuk SPA agar tidak terjadi
                                                                          // redirect
                                // aneh saat unauthorized

                                // Disable default UI yang tidak dibutuhkan untuk API
                                .formLogin(form -> form.disable())
                                .httpBasic(basic -> basic.disable())

                                // 5. Logout: endpointnya adalah AuthController POST /api/v1/auth/logout
                                // (authService.logout — audit log + MDC.clear + SecurityContextLogoutHandler).
                                // LogoutFilter Spring tidak boleh mengintersepsi URL yang sama; jika
                                // diaktifkan, request tidak pernah sampai ke controller (logout ganda).
                                .logout(logout -> logout.disable())

                                // CSRF Protection
                                .csrf(
                                                csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                                                                .csrfTokenRequestHandler(
                                                                                new SpaCsrfTokenRequestHandler()) // Handler
                                                                // khusus SPA
                                                .ignoringRequestMatchers(
                                                                "/api/v1/auth/login", // Login harus
                                                                                      // di-exempt dari
                                                                // CSRF
                                                                "/api/v1/passkey/login", // Assertion
                                                                                          // passkey:
                                                                                          // sama
                                                                                          // pola dengan
                                                                                          // /auth/login
                                                                "/api/v1/health",
                                                                "/actuator/**",
                                                                "/v3/api-docs/**",
                                                                "/swagger-ui/**"))

                                // Custom Filters
                                .addFilterAfter(
                                                new CsrfCookieFilter(),
                                                org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                                .addFilterAfter(
                                                new com.fastwork.expense.observability.UserContextMdcFilter(),
                                                org.springframework.security.web.context.SecurityContextHolderFilter.class)

                                // Security Headers (Defense in Depth)
                                .headers(
                                                headers -> headers
                                                                .contentSecurityPolicy(
                                                                                csp -> csp.policyDirectives(finalCsp))
                                                                .frameOptions(
                                                                                frame -> frame.deny()) // Mencegah
                                                                                                       // Clickjacking
                                                                .referrerPolicy(
                                                                                referrer -> referrer.policy(
                                                                                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                                                                .httpStrictTransportSecurity(
                                                                                hsts -> {
                                                                                        if (!hstsEnabled) {
                                                                                                hsts.disable();
                                                                                        } else {
                                                                                                hsts.includeSubDomains(
                                                                                                                true)
                                                                                                                .maxAgeInSeconds(
                                                                                                                                31536000); // 1
                                                                                                                                           // tahun
                                                                                        }
                                                                                }))

                                // Authorization Rules
                                .authorizeHttpRequests(
                                                auth -> auth.requestMatchers(
                                                                "/api/v1/health",
                                                                "/api/v1/auth/login",
                                                                "/api/v1/auth/reset-password",
                                                                "/api/v1/passkey/login",
                                                                "/api/v1/passkey/login/options",
                                                                "/actuator/health",
                                                                "/actuator/health/**",
                                                                "/v3/api-docs",
                                                                "/v3/api-docs/**",
                                                                "/swagger-ui.html",
                                                                "/swagger-ui/**")
                                                                .permitAll()
                                                                .anyRequest()
                                                                .authenticated())

                                // Custom Exception Handling (JSON Response)
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

        /**
         * Merakit arahan CSP: connect-src hanya diperluas bila backend-url diisi
         * (dafault kosong → tetap origin-satu lewat proxy nginx/Caddy).
         */
        static String cspDirectives(String backendUrl) {
                return StringUtils.hasText(backendUrl)
                                ? CSP + " connect-src 'self' " + backendUrl + ";"
                                : CSP + " connect-src 'self';";
        }

        /**
         * Helper method untuk menulis response error dalam format JSON yang konsisten
         */
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

        /**
         * Filter khusus untuk memastikan cookie CSRF dibuat saat pertama kali halaman
         * dimuat atau saat
         * session baru dimulai, sehingga frontend (SPA) bisa membacanya.
         */
        private static final class CsrfCookieFilter extends OncePerRequestFilter {
                @Override
                protected void doFilterInternal(
                                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                                throws ServletException, IOException {
                        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                        if (csrfToken != null) {
                                // Memanggil getToken() memaksa Spring Security untuk generate cookie jika belum
                                // ada
                                csrfToken.getToken();
                        }
                        filterChain.doFilter(request, response);
                }
        }

        /**
         * Handler CSRF khusus untuk Single Page Application (SPA). Membaca token CSRF
         * dari Header
         * (X-XSRF-TOKEN) yang dikirim oleh frontend, bukan dari form parameter
         * tradisional.
         */
        public static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
                private final CsrfTokenRequestHandler delegate = new CsrfTokenRequestAttributeHandler();

                public void handle(
                                HttpServletRequest request, HttpServletResponse response,
                                Supplier<CsrfToken> csrfToken) {
                        this.delegate.handle(request, response, csrfToken);
                }

                @Override
                public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
                        // Prioritaskan pembacaan dari Header (standar SPA seperti Axios/Fetch)
                        String headerValue = request.getHeader(csrfToken.getHeaderName());
                        return (StringUtils.hasText(headerValue))
                                        ? headerValue
                                        : this.delegate.resolveCsrfTokenValue(request, csrfToken);
                }
        }
}
