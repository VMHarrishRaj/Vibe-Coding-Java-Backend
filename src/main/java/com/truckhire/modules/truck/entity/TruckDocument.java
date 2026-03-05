package com.truckhire.modules.truck.entity;

import com.truckhire.modules.user.entity.DocumentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * TruckDocument Entity — maps to the 'truck_documents' table.
 *
 * Stores photos and documents for trucks (RC, Insurance, Permit, Photos).
 * file_path is the RELATIVE path to the stored file.
 *
 * When a new photo is uploaded for an APPROVED truck, the truck status
 * reverts to PENDING_APPROVAL so admin can re-verify.
 */
@Entity
@Table(name = "truck_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TruckDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "truck_id", nullable = false)
    private Truck truck;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "document_type_id", nullable = false)
    private DocumentType documentType;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "uploaded_at", nullable = false)
    @Builder.Default
    private OffsetDateTime uploadedAt = OffsetDateTime.now();
}
