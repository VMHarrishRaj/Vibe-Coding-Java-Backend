package com.truckhire.common.email;

/**
 * Strategy interface for email delivery.
 * Single implementation: EmailSenderImpl — always sends real SMTP email in all profiles.
 */
public interface EmailSender {

    /**
     * Send a 6-digit OTP to the given email address.
     * Used during self-registration verification.
     */
    void sendOtp(String toEmail, String otp);

    /**
     * Send a welcome email to a user created by an admin.
     * Contains their login email, temporary password, and role-specific next steps.
     * @param role  "OWNER" or "RENTER" — determines the KYC guidance in the body
     */
    void sendWelcomeEmail(String toEmail, String fullname, String tempPassword, String role);

    /**
     * Send a password reset OTP to the given email address.
     * Used during the forgot-password flow.
     */
    void sendPasswordResetOtp(String toEmail, String otp);
}
