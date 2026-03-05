package com.truckhire.common.storage;

import com.truckhire.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local Filesystem Storage Implementation.
 *
 * Stores files under the configured base directory
 * (app.file-storage.upload-dir).
 * Each file is renamed to {UUID}_{originalFilename} to prevent collisions.
 *
 * Directory structure:
 * uploads/
 * kyc/{userId}/ ← KYC documents (Aadhaar, PAN, License)
 * trucks/{truckId}/ ← Truck photos and documents
 *
 * The DB stores the RELATIVE path (e.g., "kyc/abc-123/uuid_aadhaar.jpg").
 * This makes it easy to migrate to S3 later — just change the implementation.
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path storageRoot;

    public LocalFileStorageService(
            @Value("${app.file-storage.upload-dir:./uploads}") String uploadDir) {
        this.storageRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * Create the root upload directory on startup if it doesn't exist.
     */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(storageRoot);
            log.info("File storage initialized: {}", storageRoot);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_INIT_FAILED",
                    "Could not create upload directory: " + storageRoot);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String subDirectory) {
        // Validate file
        if (file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "Cannot store an empty file");
        }

        String originalFilename = StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown");

        // Security check: reject filenames with path traversal
        if (originalFilename.contains("..")) {
            throw new BusinessException("INVALID_FILENAME",
                    "Filename contains invalid path sequence: " + originalFilename);
        }

        // Generate unique filename: {uuid}_{original}
        String storedFilename = UUID.randomUUID() + "_" + originalFilename;

        try {
            // Create sub-directory if it doesn't exist
            Path targetDir = storageRoot.resolve(subDirectory).normalize();
            Files.createDirectories(targetDir);

            // Copy file to target location
            Path targetPath = targetDir.resolve(storedFilename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            // Return relative path (for DB storage)
            String relativePath = subDirectory + "/" + storedFilename;
            log.info("File stored: {}", relativePath);
            return relativePath;

        } catch (IOException e) {
            throw new BusinessException("FILE_STORE_FAILED",
                    "Failed to store file: " + originalFilename);
        }
    }

    @Override
    public Resource loadFile(String filePath) {
        try {
            Path file = storageRoot.resolve(filePath).normalize();
            Resource resource = new UrlResource(file.toUri());

            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new BusinessException("FILE_NOT_FOUND",
                        "File not found: " + filePath);
            }
        } catch (MalformedURLException e) {
            throw new BusinessException("FILE_NOT_FOUND",
                    "File not found: " + filePath);
        }
    }

    @Override
    public void deleteFile(String filePath) {
        try {
            Path file = storageRoot.resolve(filePath).normalize();
            Files.deleteIfExists(file);
            log.info("File deleted: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", filePath, e);
        }
    }
}
