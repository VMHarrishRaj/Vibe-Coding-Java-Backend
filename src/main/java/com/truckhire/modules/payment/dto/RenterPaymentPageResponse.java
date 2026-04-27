package com.truckhire.modules.payment.dto;

import com.truckhire.common.dto.PagedResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Top-level wrapper for GET /payments/mine (RENTER role).
 * Combines the paginated booking groups with aggregate totals
 * that drive the summary card at the top of the payments screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenterPaymentPageResponse {

    private PagedResponse<RenterPaymentGroupResponse> payments;
    private RenterPaymentTotals totals;
}
