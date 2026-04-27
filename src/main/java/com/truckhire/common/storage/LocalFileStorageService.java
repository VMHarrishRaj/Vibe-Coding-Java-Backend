package com.truckhire.common.storage;

import com.truckhire.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local Filesystem Storage Implementation.
 *
 * Stores files under the configured base directory (app.file-storage.upload-dir).
 * Each file is stored as {UUID}_{sanitized-original-name} to prevent collisions
 * and ensure the path is always URL-safe.
 *
 * Directory structure:
 *   uploads/
 *     kyc/{userId}/        — KYC documents (Driver License, Passport, State ID)
 *     trucks/{truckId}/    — Truck photos and legal documents
 *     users/{userId}/      — Profile photos
 *
 * The DB stores the RELATIVE path (e.g., "trucks/abc-123/uuid_photo.jpg").
 * This makes migration to S3 straightforward — swap this implementation only.
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    // MIME types accepted for upload. Includes HEIC/HEIF for iPhone camera photos
    // and application/octet-stream as a fallback for downloads where the client
    // sends a generic content type. Extension-based detection is applied when the
    // declared MIME type is octet-stream.
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/heic",
            "image/heif",
            "application/pdf",
            "application/octet-stream"  // generic fallback — extension check applied below
    );

    // Extension → MIME type used when content-type is application/octet-stream
    private static final Map<String, String> EXTENSION_MIME_MAP = Map.of(
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "png",  "image/png",
            "heic", "image/heic",
            "heif", "image/heif",
            "pdf",  "application/pdf"
    );

    // Extensions that are permitted regardless of the declared MIME type
    private static final Set<String> ALLOWED_EXTENSIONS = EXTENSION_MIME_MAP.keySet();

    private final Path storageRoot;

    public LocalFileStorageService(
            @Value("${app.file-storage.upload-dir:./uploads}") String uploadDir) {
        this.storageRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

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
        if (file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "Cannot store an empty file");
        }

        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "upload";

        // Security: reject path traversal before any other processing
        if (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\")) {
            throw new BusinessException("INVALID_FILENAME",
                    "Filename contains invalid path characters");
        }

        String extension = extractExtension(originalFilename).toLowerCase();

        // Two-layer MIME validation:
        // Layer 1 — declared Content-Type from the HTTP header (client-provided).
        // Layer 2 — file extension cross-check, used when Content-Type is the
        //           generic "application/octet-stream" sent by some Android clients
        //           for downloaded images.
        String declaredMime = file.getContentType();
        if (declaredMime == null || !ALLOWED_MIME_TYPES.contains(declaredMime.toLowerCase())) {
            throw new BusinessException("INVALID_FILE_TYPE",
                    "File type not allowed. Accepted formats: JPEG, PNG, HEIC, PDF");
        }
        if ("application/octet-stream".equalsIgnoreCase(declaredMime)) {
            if (extension.isEmpty() || !ALLOWED_EXTENSIONS.contains(extension)) {
                throw new BusinessException("INVALID_FILE_TYPE",
                        "Cannot determine file type. Accepted formats: JPEG, PNG, HEIC, PDF");
            }
        }

        // Sanitize filename: replace every character that is not alphanumeric,
        // dot, hyphen, or underscore with an underscore. This makes the stored
        // path and the constructed fileUrl safe for use in HTTP URLs without
        // percent-encoding. The UUID prefix guarantees uniqueness so the
        // sanitized suffix is for human readability only.
        String sanitizedName = sanitizeFilename(originalFilename);
        String storedFilename = UUID.randomUUID() + "_" + sanitizedName;

        try {
            Path targetDir = storageRoot.resolve(subDirectory).normalize();

            // Guard: ensure the resolved directory is still inside storageRoot
            if (!targetDir.startsWith(storageRoot)) {
                throw new BusinessException("INVALID_SUBDIRECTORY",
                        "Storage subdirectory escapes storage root");
            }

            Files.createDirectories(targetDir);

            Path targetPath = targetDir.resolve(storedFilename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            String relativePath = subDirectory + "/" + storedFilename;
            log.info("File stored: {}", relativePath);
            return relativePath;

        } catch (IOException e) {
            throw new BusinessException("FILE_STORE_FAILED",
                    "Failed to store file: " + sanitizedName);
        }
    }

    /**
     * Replaces every character that is not alphanumeric, dot, hyphen, or
     * underscore with an underscore, then collapses consecutive underscores.
     * The extension's dot is preserved.
     * Examples:
     *   "image (5).jpg"       → "image_5_.jpg"
     *   "My Truck Photo.jpeg" → "My_Truck_Photo.jpeg"
     *   "photo@2x.png"        → "photo_2x.png"
     */
    private String sanitizeFilename(String filename) {
        String sanitized = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        // Collapse consecutive underscores for readability
        sanitized = sanitized.replaceAll("_{2,}", "_");
        return sanitized;
    }

    /**
     * Extracts the lowercase file extension, or returns an empty string if none.
     * "image (5).jpg" → "jpg"
     * "photo.JPEG"    → "jpeg"
     * "noextension"   → ""
     */
    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
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
