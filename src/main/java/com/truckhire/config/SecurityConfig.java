package com.truckhire.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckhire.common.dto.ApiResponse;
import com.truckhire.modules.auth.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security Configuration — Phase 2 (JWT-enabled).
 *
 * REQUEST FLOW:
 * Request → CORS → CSRF(disabled) → JwtAuthFilter → Authorization → Controller
 *
 * PUBLIC endpoints (no token needed):
 * - /auth/** → login, register
 * - /health → health check
 * - /swagger-ui/** → API docs
 * - /actuator/health → monitoring
 *
 * PROTECTED endpoints (JWT required):
 * - Everything else → must have valid Bearer token
 *
 * @EnableMethodSecurity: Enables fine-grained role checks:
 *                        @PreAuthorize("hasRole('ADMIN')") → only admins
 *                        @PreAuthorize("hasRole('OWNER')") → only owners
 *                        @PreAuthorize("hasAnyRole('ADMIN','OWNER')") → either
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── Authorization rules ──
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/files/trucks/**").permitAll()
                        .requestMatchers("/trucks/*/booked-dates").permitAll()
                        .requestMatchers("/trucks/*/availability").permitAll()
                        .requestMatchers("/users/document-types/kyc").permitAll()

                        // Everything else requires authentication
                        .anyRequest().authenticated())

                // ── Custom 401 response ──
                // Without this, Spring returns an HTML error page
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json");
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            ApiResponse<Void> body = ApiResponse.error(
                                    "UNAUTHORIZED", "Authentication required. Please provide a valid JWT token.");
                            response.getWriter().write(objectMapper.writeValueAsString(body));
                        }))

                // ── Register JWT filter ──
                // Run JwtAuthFilter BEFORE Spring's default username/password filter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
