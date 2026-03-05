package com.truckhire.modules.user.repository;

import com.truckhire.modules.user.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentTypeRepository extends JpaRepository<DocumentType, Integer> {

    Optional<DocumentType> findByNameAndCategory(String name, String category);

    Optional<DocumentType> findByName(String name);
}
