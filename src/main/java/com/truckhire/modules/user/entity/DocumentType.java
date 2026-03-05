package com.truckhire.modules.user.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * DocumentType Entity — maps to the 'document_types' lookup table.
 *
 * Shared between user KYC docs and truck vehicle docs.
 * The 'category' field distinguishes them:
 * - "KYC" → user documents (Aadhaar, PAN, License)
 * - "VEHICLE" → truck documents (RC, Insurance, Permit, Photo)
 */
@Entity
@Table(name = "document_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String category; // "KYC" or "VEHICLE"

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
