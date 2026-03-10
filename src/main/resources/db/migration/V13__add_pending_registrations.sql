-- V13: Add pending_registrations table for OTP-gated self-registration flow.
--
-- Row lifecycle:
--   INSERT  → when user submits register form (OTP sent)
--   UPDATE  → when user requests OTP resend (otp_code + expires_at updated)
--   DELETE  → when OTP is verified and account is created
--
-- Expired rows (expires_at < NOW()) are benign stale data.
-- No cleanup job needed — verified rows delete themselves.
-- Future: replace this table with Redis keys (TTL = 600s) in Phase 10.

CREATE TABLE pending_registrations (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email        VARCHAR(255) NOT NULL UNIQUE,   -- one pending registration per email
    phone        VARCHAR(20)  NOT NULL,
    otp_code     VARCHAR(6)   NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    payload_json TEXT         NOT NULL,          -- full RegisterRequest serialized as JSON
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pending_reg_email ON pending_registrations(email);
