package app.bartering.features.encryptedfiles.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for storing encrypted file metadata
 * Actual file content is stored as blob in database (or can be adapted for file system/S3)
 * Files are automatically cleaned up after TTL expires or after successful download
 */
object EncryptedFilesTable : Table("encrypted_files") {
    val id = varchar("id", 36).uniqueIndex() // UUID - file reference ID
    val senderId = varchar("sender_id", 255)
    val recipientId = varchar("recipient_id", 255).index()
    val filename = varchar("filename", 512) // Original filename (can be encrypted)
    val mimeType = varchar("mime_type", 255)
    val fileSize = long("file_size") // Size in bytes
    val encryptedData = blob("encrypted_data") // Encrypted file content
    val expiresAt = timestamp("expires_at").index() // TTL - auto-delete after this
    val downloaded = bool("downloaded").default(false)
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}