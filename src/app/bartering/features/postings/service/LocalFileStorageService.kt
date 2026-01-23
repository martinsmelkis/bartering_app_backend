package app.bartering.features.postings.service

import net.coobird.thumbnailator.Thumbnails
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
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
    private val log = LoggerFactory.getLogger(this::class.java)

    private val uploadDir: File
    private val baseUrl: String
    private val initialized: Boolean

    companion object {
        private const val THUMBNAIL_SIZE = 300 // 300x300px thumbnails
        private const val THUMBNAIL_SUFFIX = "_thumb"
        private const val FULL_SUFFIX = "_full"
    }

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
                log.info("Created image upload directory: {}", uploadDir.absolutePath)
            }

            // Check if directory is writable
            if (!uploadDir.canWrite()) {
                log.error("Upload directory is not writable: {}", uploadDir.absolutePath)
                false
            } else {
                log.info("Local file storage initialized: {}", uploadDir.absolutePath)
                log.info("Images will be accessible at: {}/{filename}", baseUrl)
                true
            }
        } catch (e: Exception) {
            log.error("Failed to initialize local file storage", e)
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
            val uniqueId = UUID.randomUUID()
            
            // Create full-resolution file
            val fullFileName = "${uniqueId}${FULL_SUFFIX}.$extension"
            val fullFile = File(userDir, fullFileName)
            
            // Create thumbnail file
            val thumbFileName = "${uniqueId}${THUMBNAIL_SUFFIX}.$extension"
            val thumbFile = File(userDir, thumbFileName)

            // Write full-resolution file to disk
            fullFile.outputStream().use { output ->
                output.write(imageData)
            }
            
            // Generate and save thumbnail
            try {
                val inputStream = ByteArrayInputStream(imageData)
                Thumbnails.of(inputStream)
                    .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                    .keepAspectRatio(true)
                    .outputFormat(extension)
                    .toFile(thumbFile)
                inputStream.close()
                log.debug("Generated thumbnail for userId={}: {}", userId, thumbFileName)
            } catch (e: Exception) {
                log.warn("Failed to generate thumbnail for userId={}, will use full image as fallback", userId, e)
                // If thumbnail generation fails, copy full image as thumbnail
                fullFile.copyTo(thumbFile, overwrite = true)
            }

            // Return URL (base name without suffix)
            val baseFileName = "$uniqueId.$extension"
            val imageUrl = "$baseUrl/$userId/$baseFileName"
            log.info("Uploaded image for userId={}: {} -> {}", userId, baseFileName, imageUrl)

            return imageUrl

        } catch (e: Exception) {
            log.error("Failed to upload image for userId={}", userId, e)
            throw Exception("Failed to upload image: ${e.message}")
        }
    }

    override suspend fun deleteImage(imageUrl: String): Boolean {
        if (!initialized) {
            log.warn("Local file storage is not initialized. Cannot delete image")
            return false
        }

        try {
            // Extract file path from URL
            // URL format: /api/v1/images/userId/filename.jpg
            val path = imageUrl.removePrefix(baseUrl).removePrefix("/")
            val pathParts = path.split("/")
            
            if (pathParts.size != 2) {
                log.warn("Invalid image URL format: {}", imageUrl)
                return false
            }
            
            val userId = pathParts[0]
            val fileName = pathParts[1]
            val userDir = File(uploadDir, userId)
            
            // Extract base name and extension
            val extension = fileName.substringAfterLast(".", "jpg")
            val baseName = fileName.substringBeforeLast(".")
            
            // Delete both thumbnail and full-resolution files
            val thumbFileName = "${baseName}${THUMBNAIL_SUFFIX}.$extension"
            val fullFileName = "${baseName}${FULL_SUFFIX}.$extension"
            
            val thumbFile = File(userDir, thumbFileName)
            val fullFile = File(userDir, fullFileName)
            
            var deletedCount = 0
            
            if (thumbFile.exists() && thumbFile.isFile) {
                if (thumbFile.delete()) {
                    log.info("Deleted thumbnail: {}", thumbFileName)
                    deletedCount++
                }
            }
            
            if (fullFile.exists() && fullFile.isFile) {
                if (fullFile.delete()) {
                    log.info("Deleted full image: {}", fullFileName)
                    deletedCount++
                }
            }
            
            if (deletedCount == 0) {
                log.warn("No image files found for: {}", imageUrl)
                return false
            }

            // Clean up empty user directory
            if (userDir.isDirectory && userDir.listFiles()?.isEmpty() == true) {
                userDir.delete()
                log.debug("Cleaned up empty directory: {}", userDir.name)
            }
            
            return true

        } catch (e: Exception) {
            log.error("Failed to delete image", e)
            return false
        }
    }

    override fun isInitialized(): Boolean = initialized

    /**
     * Get the physical file from a URL with optional size parameter
     * Used by the image serving route
     * 
     * @param imageUrl The base image URL (without size suffix)
     * @param size The size variant to retrieve ("thumb" or "full")
     */
    fun getFile(imageUrl: String, size: String = "full"): File? {
        return try {
            log.debug("getFile called with URL: {}, size: {}", imageUrl, size)
            log.trace("baseUrl: {}, uploadDir: {}", baseUrl, uploadDir.absolutePath)

            val path = imageUrl.removePrefix(baseUrl).removePrefix("/")
            log.trace("Extracted path: {}", path)
            
            // Parse the path to get userId and fileName
            val pathParts = path.split("/")
            if (pathParts.size != 2) {
                log.warn("Invalid path format: {}", path)
                return null
            }
            
            val userId = pathParts[0]
            val fileName = pathParts[1]
            
            // Extract base name and extension
            val extension = fileName.substringAfterLast(".", "jpg")
            val baseName = fileName.substringBeforeLast(".")
            
            // Determine which file to serve based on size parameter
            val suffix = when (size.lowercase()) {
                "thumb", "thumbnail" -> THUMBNAIL_SUFFIX
                "full", "original" -> FULL_SUFFIX
                else -> {
                    log.warn("Unknown size parameter: {}, defaulting to full", size)
                    FULL_SUFFIX
                }
            }
            
            val actualFileName = "${baseName}${suffix}.$extension"
            val file = File(uploadDir, "$userId/$actualFileName")
            
            log.trace("Full file path: {}, exists: {}, isFile: {}", file.absolutePath, file.exists(), file.isFile)

            if (file.exists() && file.isFile && file.canonicalPath.startsWith(uploadDir.canonicalPath)) {
                log.debug("File validation passed for: {}", file.name)
                file
            } else {
                log.warn("File validation failed - canonical: {}, uploadDir: {}", 
                    file.canonicalPath, uploadDir.canonicalPath)
                null
            }
        } catch (e: Exception) {
            log.error("Error in getFile for URL: {}", imageUrl, e)
            null
        }
    }
    
    /**
     * Get the physical file from a URL (backward compatibility)
     * Defaults to full size
     */
    @Deprecated("Use getFile(imageUrl, size) instead", ReplaceWith("getFile(imageUrl, \"full\")"))
    fun getFile(imageUrl: String): File? = getFile(imageUrl, "full")

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
