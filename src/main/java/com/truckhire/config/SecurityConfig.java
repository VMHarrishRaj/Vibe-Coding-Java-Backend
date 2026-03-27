package com.truckhire.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckhire.common.dto.ApiResponse;
import com.truckhire.modules.auth.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            // Enable CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Disable CSRF for REST APIs
            .csrf(AbstractHttpConfigurer::disable)

            // Stateless session for JWT
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Authorization rules
            .authorizeHttpRequests(auth -> auth

                    // Allow preflight CORS requests
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                    // Public APIs
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/api/v1/health").permitAll()
                    .requestMatchers("/actuator/health").permitAll()

                    // Swagger
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                    // Public file access
                    .requestMatchers("/api/v1/files/trucks/**").permitAll()

                    // Enum values for frontend dropdowns (public)
                    .requestMatchers("/api/v1/config/enums").permitAll()

                    // Payment: webhook receivers (no JWT — gateway calls these)
                    .requestMatchers("/api/v1/payments/webhook/**").permitAll()

                    // Stripe Connect return/refresh — Stripe drives these redirects, no JWT
                    .requestMatchers("/api/v1/stripe/connect/return", "/api/v1/stripe/connect/refresh").permitAll()

                    // Payment: public config for frontend SDK initialization
                    .requestMatchers("/api/v1/config/payment").permitAll()

                    // Public document type lookups (used by frontend before login to show KYC requirements)
                    .requestMatchers("/api/v1/users/document-types/**").permitAll()

                    // Truck availability APIs
                    .requestMatchers("/api/v1/trucks/*/booked-dates").permitAll()
                    .requestMatchers("/api/v1/trucks/*/availability").permitAll()

                    // All other APIs require authentication
                    .anyRequest().authenticated()
            )

            // Custom 401 response
            .exceptionHandling(exception ->
                    exception.authenticationEntryPoint((request, response, authException) -> {

                        response.setContentType("application/json");
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

                        ApiResponse<Void> body = ApiResponse.error(
                                "UNAUTHORIZED",
                                "Authentication required. Please provide a valid JWT token."
                        );

                        response.getWriter().write(objectMapper.writeValueAsString(body));
                    })
            )

            // Add JWT filter before Spring security authentication filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // Password encoder bean
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // CORS configuration
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));

        config.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "OPTIONS"
        ));

        config.setAllowedHeaders(List.of("*"));

        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
