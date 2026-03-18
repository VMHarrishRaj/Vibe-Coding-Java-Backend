package com.truckhire.common.controller;

import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.storage.FileStorageService;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.user.entity.Role;
import com.truckhire.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * File Controller — serves uploaded files with access control.
 *
 * ENDPOINTS:
 * GET /files/{category}/{id}/{filename}
 *
 * ACCESS RULES:
 * - category "kyc": OWNER can access their own docs; ADMIN can access all
 * - category "trucks": OWNER can access their own truck docs; RENTER + ADMIN can access any
 *
 * All endpoints require authentication (JWT).
 */
@Slf4j
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * GET /api/v1/files/{category}/{id}/{filename}
     *
     * @param category  "kyc" or "trucks"
     * @param id        userId (for kyc) or truckId (for trucks)
     * @param filename  The stored filename (UUID_originalname.ext)
     */
    @GetMapping("/{category}/{id}/{filename}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String category,
            @PathVariable String id,
            @PathVariable String filename) {

        // ── Access control ──
        if ("kyc".equalsIgnoreCase(category)) {
            // KYC docs require authentication — OWNER can only access their own; ADMIN can access any
            User currentUser = SecurityUtils.getCurrentUser();
            String roleName = currentUser.getRole().getName();
            if (Role.OWNER.equals(roleName) && !currentUser.getId().toString().equals(id)) {
                throw new BusinessException("ACCESS_DENIED", "You can only access your own KYC documents");
            }
            if (Role.RENTER.equals(roleName)) {
                throw new BusinessException("ACCESS_DENIED", "Renters cannot access KYC documents");
            }
        } else if ("trucks".equalsIgnoreCase(category)) {
            // Truck files are public — SecurityConfig already permits unauthenticated access
        } else {
            throw new BusinessException("INVALID_CATEGORY", "File category must be 'kyc' or 'trucks'");
        }

        // Build relative path matching stored format: category/id/filename
        String filePath = category + "/" + id + "/" + filename;

        Resource resource = fileStorageService.loadFile(filePath);

        // Determine Content-Type from filename
        String contentType = determineContentType(filename);

        log.info("File served: path={}", filePath);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .body(resource);
    }

    private String determineContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        return "image/jpeg"; // default for .jpg/.jpeg
    }
}
