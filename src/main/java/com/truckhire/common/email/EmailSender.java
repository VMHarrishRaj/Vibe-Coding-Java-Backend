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
     * Contains their login email and temporary password.
     */
    void sendWelcomeEmail(String toEmail, String fullname, String tempPassword);
}
