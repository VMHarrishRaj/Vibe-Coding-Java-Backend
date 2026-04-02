package com.truckhire.common.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.modules.payment.dto.PlatformSettingsResponse;
import com.truckhire.modules.payment.service.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Enum Controller — returns all dropdown/filter values used across the platform.
 *
 * GET /config/enums (public — no auth required)
 *
 * Frontend calls this once on app load and uses the values to populate
 * all filter dropdowns and form selects. This ensures frontend and backend
 * always use the same enum values.
 */
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class EnumController {

    private final PlatformSettingsService platformSettingsService;

    @GetMapping("/payment")
    public ApiResponse<PlatformSettingsResponse> getPaymentConfig() {
        return ApiResponse.success("Payment config retrieved", platformSettingsService.getSettingsResponse());
    }

    @GetMapping("/enums")
    public ApiResponse<Map<String, List<String>>> getEnums() {
        Map<String, List<String>> enums = Map.of(
                "vehicleTypes",    List.of("MINI", "STANDARD", "HEAVY"),
                "truckStatuses",   List.of("PENDING_APPROVAL", "APPROVED", "REJECTED", "INACTIVE"),
                "userStatuses",    List.of("ACTIVE", "SUSPENDED", "PENDING_VERIFICATION"),
                "userRoles",       List.of("OWNER", "RENTER"),
                "bookingStatuses", List.of("ONGOING", "UPCOMING", "COMPLETED", "REJECTED", "CANCELLED"),
                "kycDocumentTypes",List.of("DRIVER_LICENSE", "PASSPORT", "STATE_ID"),
                "truckSortOptions",List.of("price_asc", "price_desc", "newest"),
                "bankAccountTypes",List.of("CHECKING", "SAVINGS")
        );

        return ApiResponse.success("Enum values retrieved", enums);
    }
}
