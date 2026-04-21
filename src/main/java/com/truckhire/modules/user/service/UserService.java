package com.truckhire.modules.user.service;

import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.email.EmailSender;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.common.storage.FileStorageService;
import com.truckhire.modules.auth.dto.AuthResponse;
import com.truckhire.modules.auth.service.JwtService;
import com.truckhire.modules.user.dto.*;
import com.truckhire.modules.user.entity.Role;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.entity.UserStatus;
import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.truck.repository.TruckRepository;
import com.truckhire.modules.booking.repository.BookingRepository;
import com.truckhire.modules.payment.repository.PaymentTransactionRepository;
import com.truckhire.modules.user.repository.RoleRepository;
import com.truckhire.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * User Service — business logic for profile and admin user operations.
 *
 * DESIGN DECISIONS:
 *
 * 1. RELOAD FROM DB IN SERVICE (not using filter-cached user):
 * Controllers pass the user ID from SecurityContext.
 * The service reloads the user within its own transaction.
 * This ensures we always work with the latest DB state and
 * proper transaction boundaries (ACID-compliant reads/writes).
 *
 * 2. PARTIAL UPDATE PATTERN:
 * updateMyProfile only modifies fields that are non-null in the request.
 * If a field is null, it's left unchanged. This allows the client to
 * send { "city": "Bangalore" } without wiping out their name, address, etc.
 *
 * 3. ADMIN GUARDS:
 * - Cannot suspend yourself (prevents admin lockout)
 * - Cannot suspend the last active admin (system integrity)
 * - Guards are enforced in the service, not the controller
 *
 * 4. CREATE-ADMIN REUSES EXISTING INFRASTRUCTURE:
 * - Same PasswordEncoder as registration
 * - Same duplicate email/phone checks
 * - Returns AuthResponse (consistent with register endpoint)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailSender emailSender;
    private final FileStorageService fileStorageService;
    private final TruckRepository truckRepository;
    private final BookingRepository bookingRepository;
    private final PaymentTransactionRepository transactionRepository;

    // ═══════════════════════════════════════
    // USER PROFILE OPERATIONS
    // ═══════════════════════════════════════

    /**
     * Get the authenticated user's full profile.
     *
     * @param userId The ID from SecurityContext (set by JwtAuthFilter)
     * @return Full profile response
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(UUID userId) {
        User user = findActiveUserById(userId);
        return mapToProfileResponse(user);
    }

    /**
     * Update the authenticated user's profile (partial update).
     *
     * Only non-null fields in the request are applied.
     * Email, phone, role, status are NOT user-editable.
     *
     * @param userId  The ID from SecurityContext
     * @param request Fields to update (nulls are ignored)
     * @return Updated profile
     */
    @Transactional
    public UserProfileResponse updateMyProfile(UUID userId, UpdateProfileRequest request) {
        User user = findActiveUserById(userId);

        // ── Apply only non-null fields ──
        if (request.getFullname() != null) {
            user.setFullname(request.getFullname().trim());
        }
        if (request.getMiddleName() != null) {
            user.setMiddleName(request.getMiddleName());
        }
        if (request.getAlternatePhone() != null) {
            user.setAlternatePhone(request.getAlternatePhone());
        }
        if (request.getCompanyName() != null) {
            user.setCompanyName(request.getCompanyName());
        }
        if (request.getTaxId() != null) {
            user.setTaxId(request.getTaxId());
        }
        if (request.getDob() != null) {
            user.setDob(LocalDate.parse(request.getDob()));
        }
        if (request.getAddress() != null) {
            user.setAddress(request.getAddress());
        }
        if (request.getCity() != null) {
            user.setCity(request.getCity());
        }
        if (request.getState() != null) {
            user.setState(request.getState());
        }
        if (request.getCountry() != null) {
            user.setCountry(request.getCountry());
        }
        if (request.getZipcode() != null) {
            user.setZipcode(request.getZipcode());
        }
        // ── Bank details (partial update) ──
        if (request.getBankAccountName() != null) {
            user.setBankAccountName(request.getBankAccountName());
        }
        if (request.getBankAccountNumber() != null) {
            user.setBankAccountNumber(request.getBankAccountNumber());
        }
        if (request.getBankRoutingNumber() != null) {
            user.setBankRoutingNumber(request.getBankRoutingNumber());
        }
        if (request.getBankName() != null) {
            user.setBankName(request.getBankName());
        }

        User savedUser = userRepository.save(user);
        log.info("Profile updated: userId={}", savedUser.getId());
        return mapToProfileResponse(savedUser);
    }

    /**
     * Update only the authenticated user's bank details.
     *
     * PUT /users/me/bank
     *
     * Partial update — only non-null fields are applied.
     * Profile fields (name, city, etc.) are untouched.
     */
    @Transactional
    public UserProfileResponse updateMyBankDetails(UUID userId, UpdateBankRequest request) {
        User user = findActiveUserById(userId);

        if (request.getBankAccountName() != null) {
            user.setBankAccountName(request.getBankAccountName());
        }
        if (request.getBankAccountNumber() != null) {
            user.setBankAccountNumber(request.getBankAccountNumber());
        }
        if (request.getBankRoutingNumber() != null) {
            user.setBankRoutingNumber(request.getBankRoutingNumber());
        }
        if (request.getBankName() != null) {
            user.setBankName(request.getBankName());
        }

        User savedUser = userRepository.save(user);
        log.info("Bank details updated: userId={}", savedUser.getId());
        return mapToProfileResponse(savedUser);
    }

    // ═══════════════════════════════════════
    // ADMIN OPERATIONS
    // ═══════════════════════════════════════

    /**
     * List all users with optional filtering (admin-only).
     *
     * Supports filtering by:
     * - role: "ADMIN", "OWNER", "RENTER"
     * - status: "ACTIVE", "SUSPENDED", "PENDING_VERIFICATION"
     * - Both (intersection)
     * - Neither (all non-deleted users)
     *
     * Soft-deleted users are always excluded.
     */
    @Transactional(readOnly = true)
    public PagedResponse<AdminUserListResponse> getAllUsers(
            String role, String status, String q, String stripeConnected, Pageable pageable) {

        // stripeConnected filter — short-circuit entire query branch
        if (stripeConnected != null && !stripeConnected.isBlank()) {
            boolean connected = Boolean.parseBoolean(stripeConnected);
            Page<User> scPage = connected
                    ? userRepository.findOwnersWithStripeConnected(pageable)
                    : userRepository.findOwnersWithoutStripeConnected(pageable);
            return buildAdminUserPagedResponse(scPage);
        }

        Page<User> userPage;
        boolean hasRole   = role != null && !role.isBlank();
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasQ      = q != null && !q.isBlank();

        if (hasQ) {
            String keyword = "%" + q.toLowerCase().trim() + "%";
            if (hasRole && hasStatus) {
                UserStatus userStatus = parseStatus(status);
                userPage = userRepository.searchByKeywordAndRoleAndStatus(
                        keyword, role.toUpperCase(), userStatus, pageable);
            } else if (hasRole) {
                userPage = userRepository.searchByKeywordAndRole(
                        keyword, role.toUpperCase(), pageable);
            } else if (hasStatus) {
                UserStatus userStatus = parseStatus(status);
                userPage = userRepository.searchByKeywordAndStatus(
                        keyword, userStatus, pageable);
            } else {
                userPage = userRepository.searchByKeyword(keyword, pageable);
            }
        } else if (hasRole && hasStatus) {
            UserStatus userStatus = parseStatus(status);
            userPage = userRepository.findByRole_NameAndStatusAndDeletedAtIsNull(
                    role.toUpperCase(), userStatus, pageable);
        } else if (hasRole) {
            userPage = userRepository.findByRole_NameAndDeletedAtIsNull(
                    role.toUpperCase(), pageable);
        } else if (hasStatus) {
            UserStatus userStatus = parseStatus(status);
            userPage = userRepository.findByStatusAndDeletedAtIsNull(userStatus, pageable);
        } else {
            userPage = userRepository.findByDeletedAtIsNull(pageable);
        }

        return buildAdminUserPagedResponse(userPage);
    }

    private PagedResponse<AdminUserListResponse> buildAdminUserPagedResponse(Page<User> userPage) {
        List<UUID> ownerIds = userPage.getContent().stream()
                .filter(u -> "OWNER".equals(u.getRole().getName()))
                .map(User::getId)
                .collect(Collectors.toList());

        Map<UUID, Long> vehicleCountMap = new java.util.HashMap<>();
        if (!ownerIds.isEmpty()) {
            truckRepository.countTrucksByOwnerIds(ownerIds)
                    .forEach(row -> vehicleCountMap.put((UUID) row[0], (Long) row[1]));
        }

        return PagedResponse.<AdminUserListResponse>builder()
                .content(userPage.getContent().stream()
                        .map(u -> mapToAdminListResponse(u, vehicleCountMap))
                        .collect(Collectors.toList()))
                .pageNumber(userPage.getNumber())
                .pageSize(userPage.getSize())
                .totalElements(userPage.getTotalElements())
                .totalPages(userPage.getTotalPages())
                .last(userPage.isLast())
                .build();
    }

    /**
     * Get a single user's full profile (admin-only).
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserById(UUID userId) {
        User user = findActiveUserById(userId);
        return mapToProfileResponse(user);
    }

    /**
     * Admin user detail — full profile enriched with truck summary for OWNER role.
     *
     * For OWNER: fetches all non-deleted trucks + batch rental counts (3 queries total).
     * For ADMIN/RENTER: returns profile only (1 query). vehiclesOwned stays null.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getAdminUserDetail(UUID userId) {
        User user = findActiveUserById(userId);
        UserProfileResponse response = mapToProfileResponse(user);

        if (!Role.OWNER.equals(user.getRole().getName())) {
            return response;
        }

        List<Truck> trucks = truckRepository.findByOwnerIdAndDeletedAtIsNull(userId);
        if (trucks.isEmpty()) {
            response.setVehiclesOwned(List.of());
            return response;
        }

        List<UUID> truckIds = trucks.stream().map(Truck::getId).toList();
        List<Object[]> counts = bookingRepository.countBookingsPerTruck(truckIds);
        Map<UUID, Long> rentalCountMap = counts.stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));

        List<OwnedVehicleSummary> vehiclesOwned = trucks.stream()
                .map(truck -> OwnedVehicleSummary.builder()
                        .vehicleId(truck.getId().toString())
                        .registrationNumber(truck.getRegistrationNumber())
                        .model(truck.getModel())
                        .capacityTons(truck.getCapacityTons())
                        .status(truck.getStatus().name())
                        .rentals(rentalCountMap.getOrDefault(truck.getId(), 0L))
                        .build())
                .toList();

        response.setVehiclesOwned(vehiclesOwned);

        // Aggregated payment stats for this owner
        response.setLifetimeEarnings(transactionRepository.sumOwnerEarnings(userId));
        response.setPendingPayouts(transactionRepository.sumOwnerPendingPayouts(userId));
        response.setLastPaymentDate(
                transactionRepository.findLastPayoutDateForOwner(userId)
                        .map(java.time.Instant::toString)
                        .orElse(null));

        return response;
    }

    /**
     * Activate a user (set status → ACTIVE).
     *
     * Use cases:
     * - Activate a PENDING_VERIFICATION owner after KYC review
     * - Reactivate a SUSPENDED user
     */
    @Transactional
    public void activateUser(UUID userId) {
        User user = findActiveUserById(userId);

        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new BusinessException("ALREADY_ACTIVE", "User is already active");
        }

        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        log.info("User activated: userId={}, previousStatus={}", userId, user.getStatus());
    }

    /**
     * Suspend a user (set status → SUSPENDED).
     *
     * GUARDS:
     * 1. Cannot suspend yourself (prevents admin lockout)
     * 2. Cannot suspend the last active admin (system integrity)
     *
     * Suspended users cannot log in (enforced in AuthService.login).
     */
    @Transactional
    public void suspendUser(UUID userId, UUID adminId) {
        // ── Guard 1: Cannot suspend yourself ──
        if (userId.equals(adminId)) {
            throw new BusinessException("SELF_SUSPEND",
                    "You cannot suspend your own account");
        }

        User user = findActiveUserById(userId);

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new BusinessException("ALREADY_SUSPENDED", "User is already suspended");
        }

        // ── Guard 2: Cannot suspend the last active admin ──
        if (Role.ADMIN.equals(user.getRole().getName())) {
            long activeAdminCount = userRepository.countActiveAdmins();
            if (activeAdminCount <= 1) {
                throw new BusinessException("LAST_ADMIN",
                        "Cannot suspend the last active admin. Create another admin first.");
            }
        }

        user.setStatus(UserStatus.SUSPENDED);
        userRepository.save(user);
        log.info("User suspended: userId={}, by adminId={}", userId, adminId);
    }

    /**
     * Register a new OWNER or RENTER on behalf of a user (admin-only).
     *
     * POST /admin/users
     *
     * A fixed temporary password is used. Credentials are sent to the user's
     * inbox via welcome email — not returned in the response body.
     */
    @Transactional
    public AdminCreateUserResponse createUser(AdminCreateUserRequest request) {
        // ── Soft-delete-aware duplicate checks ──
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.getEmail().toLowerCase().trim())) {
            throw new BusinessException("EMAIL_TAKEN", "An account with this email already exists");
        }
        if (userRepository.existsByPhoneAndDeletedAtIsNull(request.getPhone().trim())) {
            throw new BusinessException("PHONE_TAKEN", "An account with this phone number already exists");
        }

        // ── Validate role ──
        String roleName = request.getRole().toUpperCase();
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ResourceNotFoundException("Role", "name", roleName));

        // ── Fixed temporary password for all admin-registered users ──
        String temporaryPassword = "Truck@1234";

        // ── Determine initial status ──
        // Admin-registered users start ACTIVE — admin is vouching for them.
        // NOTE (future): When RENTER KYC is introduced, change RENTER status to
        // PENDING_VERIFICATION here and trigger KYC upload flow on first login.
        // For OWNER, KYC upload is already handled post-login via POST /users/me/kyc.
        UserStatus initialStatus = UserStatus.ACTIVE;

        // ── Build User entity ──
        User user = User.builder()
                .role(role)
                .email(request.getEmail().toLowerCase().trim())
                .phone(request.getPhone().trim())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .fullname(request.getFullname().trim())
                .status(initialStatus)
                .kycVerified(false)
                // NOTE (future — RENTER KYC): When renter KYC is enabled, set
                // kycVerified = false explicitly and gate login on kycVerified flag.
                .build();

        User savedUser = userRepository.save(user);
        log.info("User created by admin: id={}, email={}, role={}",
                savedUser.getId(), savedUser.getEmail(), roleName);

        // ── Send welcome email with login credentials ──
        // Non-blocking: email failure must not roll back user creation.
        // The user is saved; a failed email is logged for manual follow-up.
        try {
            emailSender.sendWelcomeEmail(savedUser.getEmail(), savedUser.getFullname(), temporaryPassword, roleName);
        } catch (Exception e) {
            log.error("Welcome email failed for user {}: {}", savedUser.getId(), e.getMessage());
        }

        return AdminCreateUserResponse.builder()
                .userId(savedUser.getId().toString())
                .email(savedUser.getEmail())
                .role(roleName)
                .status(initialStatus.name())
                .build();
    }

    /**
     * Create a new admin user (admin-only).
     *
     * Reuses the same validation and password hashing as registration.
     * Returns AuthResponse for consistency (JWT + user ID).
     */
    @Transactional
    public AuthResponse createAdmin(CreateAdminRequest request) {
        // ── Duplicate checks (soft-delete-aware, same as createUser) ──
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.getEmail().toLowerCase().trim())) {
            throw new BusinessException("EMAIL_TAKEN",
                    "An account with this email already exists");
        }
        if (userRepository.existsByPhoneAndDeletedAtIsNull(request.getPhone().trim())) {
            throw new BusinessException("PHONE_TAKEN",
                    "An account with this phone number already exists");
        }

        Role adminRole = roleRepository.findByName(Role.ADMIN)
                .orElseThrow(() -> new ResourceNotFoundException("Role", "name", Role.ADMIN));

        User admin = User.builder()
                .role(adminRole)
                .email(request.getEmail().toLowerCase().trim())
                .phone(request.getPhone().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullname(request.getFullname().trim())
                .status(UserStatus.ACTIVE)
                .kycVerified(false)
                .build();

        User savedAdmin = userRepository.save(admin);
        log.info("Admin created: id={}, email={}", savedAdmin.getId(), savedAdmin.getEmail());

        // ── Send welcome email with login credentials ──
        // Non-blocking: email failure must not roll back admin creation.
        try {
            emailSender.sendWelcomeEmail(savedAdmin.getEmail(), savedAdmin.getFullname(), request.getPassword(), "ADMIN");
        } catch (Exception e) {
            log.error("Welcome email failed for admin {}: {}", savedAdmin.getId(), e.getMessage());
        }

        String token = jwtService.generateAccessToken(
                savedAdmin.getId(), savedAdmin.getEmail(), Role.ADMIN);

        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirationSeconds())
                .user(AuthResponse.UserInfo.builder()
                        .id(savedAdmin.getId().toString())
                        .build())
                .build();
    }

    // ═══════════════════════════════════════
    /**
     * Change password for a logged-in user.
     *
     * Requires the current password to be correct before updating.
     * This is separate from the forgot-password OTP flow — no email required.
     *
     * @param userId          The ID from SecurityContext
     * @param request         currentPassword + newPassword
     */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = findActiveUserById(userId);

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("INCORRECT_PASSWORD",
                    "Current password is incorrect.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed by user: id={}", userId);
    }

    /**
     * Upload or replace the authenticated user's profile photo.
     * Stores file under uploads/users/{userId}/ and updates profileImageUrl on the user.
     */
    @Transactional
    public UserProfileResponse uploadProfilePhoto(UUID userId, MultipartFile file) {
        User user = findActiveUserById(userId);
        String filePath = fileStorageService.storeFile(file, "users/" + userId);
        user.setProfileImageUrl(baseUrl + "/api/v1/files/" + filePath);
        userRepository.save(user);
        log.info("Profile photo uploaded: userId={}", userId);
        return mapToProfileResponse(user);
    }

    /**
     * Admin: Soft-delete a user by ID.
     * Sets deleted_at timestamp — user is hidden from all queries that filter deletedAt IS NULL.
     * Cannot delete the currently authenticated admin (guard in controller).
     */
    @Transactional
    public void deleteUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.isDeleted()) {
            throw new BusinessException("USER_ALREADY_DELETED", "User has already been deleted");
        }

        user.softDelete();
        userRepository.save(user);
        log.info("User soft-deleted by admin: id={}", userId);
    }

    // ═══════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════

    /**
     * Find a user by ID, ensuring they exist and are not soft-deleted.
     */
    private User findActiveUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.isDeleted()) {
            throw new ResourceNotFoundException("User", "id", userId);
        }

        return user;
    }

    /**
     * Parse a status string into the UserStatus enum.
     * Throws BusinessException for invalid values.
     */
    private UserStatus parseStatus(String status) {
        try {
            return UserStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_STATUS",
                    "Invalid status: " + status + ". Must be ACTIVE, SUSPENDED, or PENDING_VERIFICATION");
        }
    }

    /**
     * Map User entity to full profile response.
     */
    private UserProfileResponse mapToProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId().toString())
                .fullname(user.getFullname())
                .middleName(user.getMiddleName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .alternatePhone(user.getAlternatePhone())
                .companyName(user.getCompanyName())
                .taxId(user.getTaxId())
                .role(user.getRole().getName())
                .status(user.getStatus().name())
                .displayStatus(toDisplayUserStatus(user.getStatus()))
                .dob(user.getDob() != null ? user.getDob().toString() : null)
                .address(user.getAddress())
                .city(user.getCity())
                .state(user.getState())
                .country(user.getCountry())
                .zipcode(user.getZipcode())
                .kycVerified(user.isKycVerified())
                .profileImageUrl(user.getProfileImageUrl())
                .bankAccountName(user.getBankAccountName())
                .bankAccountNumber(user.getBankAccountNumber())
                .bankRoutingNumber(user.getBankRoutingNumber())
                .bankName(user.getBankName())
                .stripeConnected(user.getStripeAccountId() != null && !user.getStripeAccountId().isBlank())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .build();
    }

    /**
     * Map User entity to condensed admin list response.
     * vehicleCountMap is pre-loaded in bulk by the caller — no per-user DB query here.
     */
    private AdminUserListResponse mapToAdminListResponse(User user, Map<UUID, Long> vehicleCountMap) {
        long vehicleCount = vehicleCountMap.getOrDefault(user.getId(), 0L);

        return AdminUserListResponse.builder()
                .id(user.getId().toString())
                .fullname(user.getFullname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole().getName())
                .status(user.getStatus().name())
                .displayStatus(toDisplayUserStatus(user.getStatus()))
                .kycVerified(user.isKycVerified())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .vehicleCount(vehicleCount)
                .build();
    }

    private String toDisplayUserStatus(UserStatus status) {
        return switch (status) {
            case ACTIVE               -> "Active";
            case PENDING_VERIFICATION, PENDING -> "Waiting for Approval";
            case SUSPENDED            -> "Inactive";
            case REJECTED             -> "Rejected";
        };
    }
}
