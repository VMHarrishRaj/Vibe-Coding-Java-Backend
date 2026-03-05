-- ═══════════════════════════════════════════════════
-- V5__create_user_documents_table.sql
-- ═══════════════════════════════════════════════════
-- KYC document storage for owners.
-- Owners upload docs (Aadhaar, PAN, License) during
-- or after registration. Admin verifies them.
--
-- file_path stores the RELATIVE path to the uploaded
-- file (e.g., "kyc/user-uuid/uuid_aadhaar.jpg").
-- The actual file lives on disk (dev) or S3 (prod).
-- ═══════════════════════════════════════════════════

CREATE TABLE user_documents (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL REFERENCES users(id),
    document_type_id    INT          NOT NULL REFERENCES document_types(id),
    file_path           VARCHAR(1000) NOT NULL,
    verification_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    rejection_reason    TEXT,
    uploaded_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    verified_at         TIMESTAMPTZ,

    CONSTRAINT chk_doc_verification_status
        CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);

-- Index for "get all docs for a user"
CREATE INDEX idx_user_documents_user ON user_documents(user_id);

-- Index for "pending verification" admin view
CREATE INDEX idx_user_documents_status ON user_documents(verification_status);
