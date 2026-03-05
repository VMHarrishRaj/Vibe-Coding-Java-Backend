package com.truckhire.modules.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * UserDocument Entity — maps to the 'user_documents' table.
 *
 * Stores KYC documents uploaded by owners.
 * file_path is the RELATIVE path to the stored file.
 * Admin reviews the document and sets verification_status.
 *
 * LIFECYCLE:
 * Owner uploads doc → PENDING → Admin verifies → VERIFIED
 * → Admin rejects → REJECTED (with reason)
 */
@Entity
@Table(name = "user_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "document_type_id", nullable = false)
    private DocumentType documentType;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "uploaded_at", nullable = false)
    @Builder.Default
    private OffsetDateTime uploadedAt = OffsetDateTime.now();

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;
}
