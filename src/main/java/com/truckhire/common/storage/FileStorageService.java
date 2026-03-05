package com.truckhire.common.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * File Storage Abstraction.
 *
 * Interface-based design allows swapping storage backends:
 * - LocalFileStorageService → local filesystem (dev)
 * - S3FileStorageService → AWS S3 (prod, future)
 *
 * All file paths returned/stored are RELATIVE to the base storage directory.
 * The DB stores only the relative folder/file path — never absolute paths.
 */
public interface FileStorageService {

    /**
     * Store a file in the given sub-directory.
     *
     * @param file         The uploaded file
     * @param subDirectory Sub-directory within the storage root (e.g.,
     *                     "kyc/user-uuid" or "trucks/truck-uuid")
     * @return The relative path to the stored file (for DB storage)
     */
    String storeFile(MultipartFile file, String subDirectory);

    /**
     * Load a previously stored file as a Resource (for download).
     *
     * @param filePath The relative path returned by storeFile
     * @return Spring Resource wrapping the file
     */
    Resource loadFile(String filePath);

    /**
     * Delete a stored file.
     *
     * @param filePath The relative path returned by storeFile
     */
    void deleteFile(String filePath);
}
