package org.barter.features.encryptedfiles.model

import kotlinx.serialization.Serializable

/**
 * Data transfer object for encrypted file metadata
 */
@Serializable
data class EncryptedFileDto(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val expiresAt: Long, // Unix timestamp
    val downloaded: Boolean = false
)

/**
 * DTO for file metadata without the actual file content
 */
@Serializable
data class FileMetadataDto(
    val id: String,
    val senderId: String,
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val expiresAt: Long
)

/**
 * Response for successful file upload
 */
@Serializable
data class FileUploadResponse(
    val success: Boolean,
    val fileId: String,
    val expiresAt: Long,
    val message: String
)

/**
 * Response for file operations errors
 */
@Serializable
data class FileErrorResponse(
    val success: Boolean = false,
    val error: String
)

/**
 * Response for pending files query
 */
@Serializable
data class PendingFilesResponse(
    val files: List<FileMetadataDto>
)
