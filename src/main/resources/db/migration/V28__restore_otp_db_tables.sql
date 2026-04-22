-- V28: Restore OTP tables dropped in V27.
-- OTP storage reverted to DB — Redis-backed OTP deferred to a later phase.
-- pending_registrations and password_reset_tokens are recreated as they were in V13/V14.

CREATE TABLE IF NOT EXISTS pending_registrations (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email        VARCHAR(255) NOT NULL UNIQUE,
    phone        VARCHAR(20)  NOT NULL,
    otp_code     VARCHAR(6)   NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    payload_json TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pending_reg_email ON pending_registrations(email);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    otp_code    VARCHAR(6)   NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pwd_reset_email ON password_reset_tokens(email);
