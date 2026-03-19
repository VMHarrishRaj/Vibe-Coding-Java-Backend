package com.truckhire.common.controller;

import com.truckhire.common.dto.ApiResponse;
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
public class EnumController {

    @GetMapping("/enums")
    public ApiResponse<Map<String, List<String>>> getEnums() {
        Map<String, List<String>> enums = Map.of(
                "vehicleTypes",    List.of("MINI", "STANDARD", "HEAVY"),
                "truckStatuses",   List.of("PENDING_APPROVAL", "APPROVED", "REJECTED", "INACTIVE"),
                "userStatuses",    List.of("ACTIVE", "SUSPENDED", "PENDING_VERIFICATION"),
                "userRoles",       List.of("OWNER", "RENTER"),
                "bookingStatuses", List.of("PENDING", "CONFIRMED", "ACTIVE", "COMPLETED", "REJECTED", "CANCELLED"),
                "kycDocumentTypes",List.of("DRIVER_LICENSE", "PASSPORT", "STATE_ID"),
                "truckSortOptions",List.of("price_asc", "price_desc", "newest"),
                "bankAccountTypes",List.of("CHECKING", "SAVINGS")
        );

        return ApiResponse.success("Enum values retrieved", enums);
    }
}
