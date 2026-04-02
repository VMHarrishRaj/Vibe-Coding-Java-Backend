package com.truckhire.modules.addon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Returned by GET /trucks/{truckId}/addons
 * Shows what a renter can select before booking this truck.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TruckAddonsResponse {

    /** Auto-applied insurance (already attached to truck by owner). Null if truck is uninsured. */
    private TruckInsuranceResponse insurance;

    /** Available RSA providers (from admin catalog — renter picks one or none) */
    private List<AddonServiceTypeResponse> rsaOptions;

    /** Equipment registered by the owner for this truck */
    private List<TruckEquipmentResponse> equipment;
}
