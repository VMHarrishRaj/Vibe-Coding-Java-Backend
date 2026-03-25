package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for POST /owners/me/stripe/connect.
 * Contains the Stripe-hosted onboarding URL the owner must visit to
 * complete bank account setup and accept Stripe's ToS.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StripeConnectResponse {
    private String onboardingUrl;
}
