package com.truckhire.common.util;

import com.truckhire.common.exception.BusinessException;
import com.truckhire.modules.user.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Security utility for extracting the authenticated user from Spring's
 * SecurityContext.
 *
 * DESIGN DECISION:
 * Centralized user extraction avoids casting boilerplate in every controller.
 * The principal is set as the User entity by JwtAuthFilter (Phase 2).
 *
 * USAGE IN CONTROLLERS:
 * User currentUser = SecurityUtils.getCurrentUser();
 * UUID userId = currentUser.getId();
 *
 * WHY NOT @AuthenticationPrincipal:
 * Our User entity doesn't implement Spring's UserDetails interface.
 * 
 * @AuthenticationPrincipal works but requires extra config. A utility
 *                          method is more explicit and avoids magic
 *                          annotations.
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // Utility class — no instantiation
    }

    /**
     * Get the currently authenticated User entity.
     *
     * @return The User entity from the JWT-authenticated SecurityContext
     * @throws BusinessException if no user is authenticated
     */
    public static User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("UNAUTHORIZED", "No authenticated user in security context");
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof User)) {
            throw new BusinessException("UNAUTHORIZED", "Invalid authentication principal type");
        }

        return (User) principal;
    }
}
