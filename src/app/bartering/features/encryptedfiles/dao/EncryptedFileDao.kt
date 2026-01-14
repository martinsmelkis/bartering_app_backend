package app.bartering.features.encryptedfiles.dao

import app.bartering.features.encryptedfiles.model.EncryptedFileDto

/**
 * Interface for encrypted file data access operations
 */
interface EncryptedFileDao {
    /**
     * Store an encrypted file
     * @return true if successful
     */
    suspend fun storeEncryptedFile(fileDto: EncryptedFileDto, encryptedData: ByteArray): Boolean

    /**
     * Get encrypted file metadata by ID
     */
    suspend fun getFileMetadata(fileId: String): EncryptedFileDto?

    /**
     * Get encrypted file content by ID
     * @return encrypted file bytes or null if not found
     */
    suspend fun getEncryptedFileContent(fileId: String): ByteArray?

    /**
     * Get complete file info (metadata + content)
     */
    suspend fun getEncryptedFile(fileId: String): Pair<EncryptedFileDto, ByteArray>?

    /**
     * Mark file as downloaded
     */
    suspend fun markAsDownloaded(fileId: String): Boolean

    /**
     * Delete expired or downloaded files (cleanup)
     * @return number of files deleted
     */
    suspend fun deleteExpiredFiles(): Int

    /**
     * Delete a specific file
     */
    suspend fun deleteFile(fileId: String): Boolean

    /**
     * Get all pending file notifications for a user (files they haven't downloaded yet)
     */
    suspend fun getPendingFiles(userId: String): List<EncryptedFileDto>
}
