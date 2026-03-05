package com.truckhire.modules.user.repository;

import com.truckhire.modules.user.entity.UserDocument;
import com.truckhire.modules.user.entity.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserDocumentRepository extends JpaRepository<UserDocument, UUID> {

    List<UserDocument> findByUserId(UUID userId);

    List<UserDocument> findByUserIdAndVerificationStatus(UUID userId, VerificationStatus status);

    boolean existsByUserIdAndDocumentType_Name(UUID userId, String documentTypeName);
}
