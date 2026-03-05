package com.truckhire.modules.user.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.user.dto.KycDocumentResponse;
import com.truckhire.modules.user.dto.UpdateProfileRequest;
import com.truckhire.modules.user.dto.UserProfileResponse;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.service.KycService;
import com.truckhire.modules.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * User Controller — authenticated user profile endpoints.
 *
 * ENDPOINTS:
 * GET /api/v1/users/me → Get own profile
 * PUT /api/v1/users/me → Update own profile
 * POST /api/v1/users/me/kyc → Upload a KYC document (multipart)
 * GET /api/v1/users/me/kyc → List my KYC documents
 *
 * AUTH REQUIRED: All endpoints need a valid JWT (enforced by SecurityConfig).
 * No role restriction at class level — KYC upload is owner-only (enforced in
 * KycService).
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final KycService kycService;

    /**
     * GET /api/v1/users/me
     *
     * Returns the full profile of the authenticated user.
     * This is the endpoint clients call after login to get user details
     * (since auth response only returns user ID).
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile() {
        User currentUser = SecurityUtils.getCurrentUser();
        UserProfileResponse profile = userService.getMyProfile(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Profile retrieved", profile));
    }

    /**
     * PUT /api/v1/users/me
     *
     * Update the authenticated user's profile.
     * Uses partial update pattern — only non-null fields are changed.
     */
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request) {

        User currentUser = SecurityUtils.getCurrentUser();
        UserProfileResponse updatedProfile = userService.updateMyProfile(
                currentUser.getId(), request);

        return ResponseEntity.ok(ApiResponse.success("Profile updated", updatedProfile));
    }

    // ═══════════════════════════════════════
    // KYC DOCUMENT ENDPOINTS
    // ═══════════════════════════════════════

    /**
     * POST /api/v1/users/me/kyc
     *
     * Upload a KYC document. Multipart form-data with:
     * - file: the document file (jpg, png, pdf)
     * - documentType: AADHAAR, PAN, or LICENSE
     *
     * Only OWNERs can upload KYC docs (enforced in KycService).
     */
    @PostMapping("/me/kyc")
    public ResponseEntity<ApiResponse<KycDocumentResponse>> uploadKycDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType) {

        User currentUser = SecurityUtils.getCurrentUser();
        KycDocumentResponse response = kycService.uploadKycDocument(
                currentUser.getId(), documentType, file);

        return ResponseEntity.ok(ApiResponse.success("KYC document uploaded", response));
    }

    /**
     * GET /api/v1/users/me/kyc
     *
     * List the authenticated user's KYC documents.
     */
    @GetMapping("/me/kyc")
    public ResponseEntity<ApiResponse<List<KycDocumentResponse>>> getMyKycDocuments() {
        User currentUser = SecurityUtils.getCurrentUser();
        List<KycDocumentResponse> documents = kycService.getMyKycDocuments(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("KYC documents retrieved", documents));
    }
}
