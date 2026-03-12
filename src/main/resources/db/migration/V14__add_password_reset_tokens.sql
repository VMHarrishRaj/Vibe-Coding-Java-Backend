-- V14: Add password_reset_tokens table for forgot-password OTP flow.
-- One row per email — upserted on each forgot-password request.
-- Deleted on successful reset (OTP consumed).

CREATE TABLE password_reset_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    otp_code    VARCHAR(6)   NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pwd_reset_email ON password_reset_tokens(email);
