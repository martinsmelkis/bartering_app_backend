package app.bartering.features.postings.service

import java.io.File
import java.util.*

/**
 * Local file storage implementation.
 * Stores images on the server's filesystem.
 *
 * Configuration:
 * - IMAGE_UPLOAD_DIR: Directory path for uploads (default: "uploads/images")
 * - IMAGE_BASE_URL: Base URL for serving images (default: "/api/v1/images")
 */
class LocalFileStorageService : ImageStorageService {

    private val uploadDir: File
    private val baseUrl: String
    private val initialized: Boolean

    init {
        // Get upload directory from environment or use default
        val uploadPath = System.getenv("IMAGE_UPLOAD_DIR") ?: "uploads/images"
        uploadDir = File(uploadPath)

        // Get base URL from environment or use default
        baseUrl = System.getenv("IMAGE_BASE_URL") ?: "/api/v1/images"

        // Create directory if it doesn't exist
        initialized = try {
            if (!uploadDir.exists()) {
                uploadDir.mkdirs()
                println("✅ Created image upload directory: ${uploadDir.absolutePath}")
            }

            // Check if directory is writable
            if (!uploadDir.canWrite()) {
                println("❌ Upload directory is not writable: ${uploadDir.absolutePath}")
                false
            } else {
                println("✅ Local file storage initialized: ${uploadDir.absolutePath}")
                println("   Images will be accessible at: $baseUrl/{filename}")
                true
            }
        } catch (e: Exception) {
            println("❌ Failed to initialize local file storage: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    override suspend fun uploadImage(
        imageData: ByteArray,
        userId: String,
        fileName: String,
        contentType: String
    ): String {
        if (!initialized) {
            throw IllegalStateException("Local file storage is not initialized properly")
        }

        try {
            // Validate image size (10MB limit)
            val maxSize = 10 * 1024 * 1024 // 10MB
            if (imageData.size > maxSize) {
                throw IllegalArgumentException("Image too large: ${imageData.size / 1024 / 1024}MB. Max: 10MB")
            }

            // Validate content type
            if (!contentType.startsWith("image/")) {
                throw IllegalArgumentException("Invalid content type: $contentType. Must be an image.")
            }

            // Create user-specific subdirectory
            val userDir = File(uploadDir, userId)
            if (!userDir.exists()) {
                userDir.mkdirs()
            }

            // Generate unique filename
            val extension = fileName.substringAfterLast(".", "jpg")
            val uniqueFileName = "${UUID.randomUUID()}.$extension"
            val file = File(userDir, uniqueFileName)

            // Write file to disk
            file.outputStream().use { output ->
                output.write(imageData)
            }

            // Return URL
            val imageUrl = "$baseUrl/$userId/$uniqueFileName"
            println("✅ Uploaded image: ${file.absolutePath} → $imageUrl")

            return imageUrl

        } catch (e: Exception) {
            println("❌ Failed to upload image: ${e.message}")
            e.printStackTrace()
            throw Exception("Failed to upload image: ${e.message}")
        }
    }

    override suspend fun deleteImage(imageUrl: String): Boolean {
        if (!initialized) {
            println("⚠️  Local file storage is not initialized. Cannot delete image.")
            return false
        }

        try {
            // Extract file path from URL
            // URL format: /api/v1/images/userId/filename.jpg
            val path = imageUrl.removePrefix(baseUrl).removePrefix("/")
            val file = File(uploadDir, path)

            if (file.exists() && file.isFile) {
                val deleted = file.delete()
                if (deleted) {
                    println("✅ Deleted image: ${file.absolutePath}")

                    // Clean up empty user directory
                    val parentDir = file.parentFile
                    if (parentDir != null && parentDir.isDirectory && parentDir.listFiles()
                            ?.isEmpty() == true
                    ) {
                        parentDir.delete()
                        println("   Cleaned up empty directory: ${parentDir.absolutePath}")
                    }
                }
                return deleted
            } else {
                println("⚠️  Image not found: ${file.absolutePath}")
                return false
            }

        } catch (e: Exception) {
            println("❌ Failed to delete image: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    override fun isInitialized(): Boolean = initialized

    /**
     * Get the physical file from a URL
     * Used by the image serving route
     */
    fun getFile(imageUrl: String): File? {
        return try {
            println("📷 getFile called with URL: $imageUrl")
            println("📷 baseUrl: $baseUrl")
            println("📷 uploadDir: ${uploadDir.absolutePath}")

            val path = imageUrl.removePrefix(baseUrl).removePrefix("/")
            println("📷 Extracted path: $path")

            val file = File(uploadDir, path)
            println("📷 Full file path: ${file.absolutePath}")
            println("📷 File exists: ${file.exists()}, isFile: ${file.isFile}")

            if (file.exists() && file.isFile && file.canonicalPath.startsWith(uploadDir.canonicalPath)) {
                println("📷 File validation passed")
                file
            } else {
                println("📷 File validation failed")
                println("   Canonical path: ${file.canonicalPath}")
                println("   Upload dir canonical: ${uploadDir.canonicalPath}")
                null
            }
        } catch (e: Exception) {
            println("❌ Error in getFile: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Get storage statistics
     */
    fun getStats(): StorageStats {
        return try {
            val totalFiles = uploadDir.walkTopDown().count { it.isFile }
            val totalSize = uploadDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()

            StorageStats(
                totalFiles = totalFiles,
                totalSizeBytes = totalSize,
                totalSizeMB = totalSize / (1024 * 1024),
                uploadDirectory = uploadDir.absolutePath
            )
        } catch (_: Exception) {
            StorageStats(0, 0, 0, uploadDir.absolutePath)
        }
    }
}

/**
 * Storage statistics data class
 */
data class StorageStats(
    val totalFiles: Int,
    val totalSizeBytes: Long,
    val totalSizeMB: Long,
    val uploadDirectory: String
)
