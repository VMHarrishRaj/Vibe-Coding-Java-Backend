package com.truckhire.modules.user.entity;

/**
 * Verification status for KYC documents.
 *
 * PENDING → just uploaded, awaiting admin review
 * VERIFIED → admin confirmed the document is valid
 * REJECTED → admin rejected the document (reason stored)
 */
public enum VerificationStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
