package com.truckhire.modules.auth.security;

import com.truckhire.modules.auth.service.JwtService;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JWT Authentication Filter — intercepts EVERY request.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ REQUEST FLOW: │
 * │ │
 * │ Client Request │
 * │ ↓ │
 * │ JwtAuthFilter (this class) │
 * │ → Extract token from Authorization header │
 * │ → Validate token (signature + expiry) │
 * │ → Load user from DB (by user ID in token) │
 * │ → Set authentication in SecurityContext │
 * │ ↓ │
 * │ Spring Security Authorization │
 * │ → Check @PreAuthorize("hasRole('ADMIN')") etc. │
 * │ ↓ │
 * │ Controller │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * OncePerRequestFilter ensures this runs exactly once per request
 * (even with internal forwards/redirects).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            // ── Step 1: Extract JWT from Authorization header ──
            String token = extractTokenFromRequest(request);

            if (token != null && jwtService.validateToken(token)) {

                // ── Step 2: Get user ID and role from token ──
                UUID userId = jwtService.getUserIdFromToken(token);
                String role = jwtService.getRoleFromToken(token);

                // ── Step 3: Load user from database ──
                // We verify the user still exists and is active
                User user = userRepository.findById(userId).orElse(null);

                if (user != null && user.getDeletedAt() == null) {

                    // ── Step 4: Create authentication object ──
                    // SimpleGrantedAuthority with "ROLE_" prefix enables:
                    // @PreAuthorize("hasRole('ADMIN')") → checks for ROLE_ADMIN
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            user, // principal (the user object)
                            null, // credentials (already validated)
                            List.of(new SimpleGrantedAuthority("ROLE_" + role)));

                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    // ── Step 5: Set in SecurityContext ──
                    // After this, the request is "authenticated"
                    // Controllers can access the user via SecurityContextHolder
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication: {}", ex.getMessage());
        }

        // ── Always continue the filter chain ──
        // Even without authentication, the request continues.
        // Spring Security's authorization rules will block it if needed.
        filterChain.doFilter(request, response);
    }

    /**
     * Extract the Bearer token from the Authorization header.
     *
     * Expected format: "Bearer eyJhbGciOiJIUzI1NiJ9..."
     * Returns the token string without "Bearer " prefix.
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7); // Remove "Bearer " prefix
        }
        return null;
    }
}
