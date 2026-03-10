package com.truckhire.modules.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response returned after a successful OTP send or resend.
 * The frontend uses this to know which email address to display
 * on the OTP entry screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtpSentResponse {
    private String email;
    private String message;
}
