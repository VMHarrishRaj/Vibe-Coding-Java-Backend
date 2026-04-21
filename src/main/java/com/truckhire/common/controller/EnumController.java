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

    @GetMapping("/states")
    public ApiResponse<List<Map<String, String>>> getUsStates() {
        List<Map<String, String>> states = List.of(
            Map.of("code","AL","name","Alabama"), Map.of("code","AK","name","Alaska"),
            Map.of("code","AZ","name","Arizona"), Map.of("code","AR","name","Arkansas"),
            Map.of("code","CA","name","California"), Map.of("code","CO","name","Colorado"),
            Map.of("code","CT","name","Connecticut"), Map.of("code","DE","name","Delaware"),
            Map.of("code","FL","name","Florida"), Map.of("code","GA","name","Georgia"),
            Map.of("code","HI","name","Hawaii"), Map.of("code","ID","name","Idaho"),
            Map.of("code","IL","name","Illinois"), Map.of("code","IN","name","Indiana"),
            Map.of("code","IA","name","Iowa"), Map.of("code","KS","name","Kansas"),
            Map.of("code","KY","name","Kentucky"), Map.of("code","LA","name","Louisiana"),
            Map.of("code","ME","name","Maine"), Map.of("code","MD","name","Maryland"),
            Map.of("code","MA","name","Massachusetts"), Map.of("code","MI","name","Michigan"),
            Map.of("code","MN","name","Minnesota"), Map.of("code","MS","name","Mississippi"),
            Map.of("code","MO","name","Missouri"), Map.of("code","MT","name","Montana"),
            Map.of("code","NE","name","Nebraska"), Map.of("code","NV","name","Nevada"),
            Map.of("code","NH","name","New Hampshire"), Map.of("code","NJ","name","New Jersey"),
            Map.of("code","NM","name","New Mexico"), Map.of("code","NY","name","New York"),
            Map.of("code","NC","name","North Carolina"), Map.of("code","ND","name","North Dakota"),
            Map.of("code","OH","name","Ohio"), Map.of("code","OK","name","Oklahoma"),
            Map.of("code","OR","name","Oregon"), Map.of("code","PA","name","Pennsylvania"),
            Map.of("code","RI","name","Rhode Island"), Map.of("code","SC","name","South Carolina"),
            Map.of("code","SD","name","South Dakota"), Map.of("code","TN","name","Tennessee"),
            Map.of("code","TX","name","Texas"), Map.of("code","UT","name","Utah"),
            Map.of("code","VT","name","Vermont"), Map.of("code","VA","name","Virginia"),
            Map.of("code","WA","name","Washington"), Map.of("code","WV","name","West Virginia"),
            Map.of("code","WI","name","Wisconsin"), Map.of("code","WY","name","Wyoming")
        );
        return ApiResponse.success("US states retrieved", states);
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
