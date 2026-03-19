package com.truckhire.common.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * EmailSender implementation — sends HTML emails via SMTP (Gmail).
 * Credentials come from environment variables (SMTP_USER, SMTP_PASSWORD).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSenderImpl implements EmailSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Override
    public void sendOtp(String toEmail, String otp) {
        String subject = "TruckRental — Your Verification Code";
        String html = """
                <html>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6;">
                        <h2>Hello,</h2>
                        <p>We received a request to verify your email for your <b>TruckRental</b> account.</p>
                        <p>Your One-Time Password (OTP) is:</p>
                        <h2 style="color: #2E86C1;">%s</h2>
                        <p>This OTP is valid for the next <b>10 minutes</b>. Please do not share it with anyone.</p>
                        <p>If you did not request this, please ignore this email. Your account is safe.</p>
                        <hr>
                        <p style="font-size: 12px; color: gray;">This is an automated email from <b>TruckRental</b>. Do not reply to this message.</p>
                    </body>
                </html>
                """.formatted(otp);

        sendHtml(toEmail, subject, html);
        log.info("OTP email sent to {}", toEmail);
    }

    @Override
    public void sendWelcomeEmail(String toEmail, String fullname, String tempPassword, String role) {
        String subject = "Welcome to TruckRental — Your Account Credentials";

        String roleSpecificMessage = "OWNER".equalsIgnoreCase(role)
                ? "Your TruckRental <b>Owner</b> account has been set up by an administrator. " +
                  "To start listing your trucks, log in to the mobile app, upload your KYC documents, and wait for admin verification."
                : "Your TruckRental <b>Renter</b> account has been set up by an administrator. " +
                  "To start booking trucks, log in to the mobile app, upload your KYC documents, and wait for admin verification.";

        String html = """
                <html>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6;">
                        <h2>Welcome %s,</h2>
                        <p>%s</p>
                        <p>Your login credentials are:</p>
                        <ul>
                            <li><b>Email:</b> %s</li>
                            <li><b>Password:</b> %s</li>
                        </ul>
                        <p>Please log in using the <b>TruckRental mobile app</b>.</p>
                        <p style="color: gray; font-size: 13px;">&#128241; App download link will be shared with you separately.</p>
                        <p style="color: red;">&#9888;&#65039; For security purposes, please change your password after your first login.</p>
                        <hr>
                        <p style="font-size: 12px; color: gray;">This is an automated email from <b>TruckRental</b>. Do not reply to this message.</p>
                    </body>
                </html>
                """.formatted(fullname, roleSpecificMessage, toEmail, tempPassword);

        sendHtml(toEmail, subject, html);
        log.info("Welcome email sent to {}", toEmail);
    }

    @Override
    public void sendPasswordResetOtp(String toEmail, String otp) {
        String subject = "TruckRental — Password Reset Code";
        String html = """
                <html>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6;">
                        <h2>Hello,</h2>
                        <p>We received a request to reset your <b>TruckRental</b> account password.</p>
                        <p>Your Password Reset Code is:</p>
                        <h2 style="color: #2E86C1;">%s</h2>
                        <p>This code is valid for the next <b>10 minutes</b>. Please do not share it with anyone.</p>
                        <p>If you did not request this, you can safely ignore this email. Your account is safe.</p>
                        <hr>
                        <p style="font-size: 12px; color: gray;">This is an automated email from <b>TruckRental</b>. Do not reply to this message.</p>
                    </body>
                </html>
                """.formatted(otp);

        sendHtml(toEmail, subject, html);
        log.info("Password reset OTP email sent to {}", toEmail);
    }

    private void sendHtml(String toEmail, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Email delivery failed", e);
        }
    }
}
