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

    /**
     * Represents parsed components of an image path
     */
    private data class ImagePathInfo(
        val userId: String,
        val fileName: String,
        val baseName: String,
        val extension: String
    )

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

            // Generate unique filename components
            val extension = fileName.substringAfterLast(".", "jpg")
            val uniqueId = UUID.randomUUID().toString()
            
            // Build file names using utility method
            val fullFileName = buildFileName(uniqueId, extension, FULL_SUFFIX)
            val thumbFileName = buildFileName(uniqueId, extension, THUMBNAIL_SUFFIX)
            
            val fullFile = File(userDir, fullFileName)
            val thumbFile = File(userDir, thumbFileName)

            // Write full-resolution file to disk
            fullFile.outputStream().use { output ->
                output.write(imageData)
            }
            
            // Generate and save thumbnail using utility method
            if (!generateThumbnail(imageData, thumbFile, extension)) {
                log.warn("Using full image as thumbnail fallback for userId={}", userId)
                fullFile.copyTo(thumbFile, overwrite = true)
            } else {
                log.debug("Generated thumbnail for userId={}: {}", userId, thumbFileName)
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
            // Parse URL using utility method
            val pathInfo = parseImageUrl(imageUrl) ?: run {
                log.warn("Invalid image URL format: {}", imageUrl)
                return false
            }
            
            val userDir = File(uploadDir, pathInfo.userId)
            
            // Build file names for both versions
            val thumbFileName = buildFileName(pathInfo.baseName, pathInfo.extension, THUMBNAIL_SUFFIX)
            val fullFileName = buildFileName(pathInfo.baseName, pathInfo.extension, FULL_SUFFIX)
            
            val thumbFile = File(userDir, thumbFileName)
            val fullFile = File(userDir, fullFileName)
            
            // Delete both files
            var deletedCount = 0
            
            if (thumbFile.exists() && thumbFile.delete()) {
                log.info("Deleted thumbnail: {}", thumbFileName)
                deletedCount++
            }
            
            if (fullFile.exists() && fullFile.delete()) {
                log.info("Deleted full image: {}", fullFileName)
                deletedCount++
            }
            
            if (deletedCount == 0) {
                log.warn("No image files found for: {}", imageUrl)
                return false
            }

            // Clean up empty directory
            cleanupEmptyDirectory(userDir)
            
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
            
            // Parse URL using utility method
            val pathInfo = parseImageUrl(imageUrl) ?: return null
            
            // Determine size suffix using utility method
            val suffix = getSizeSuffix(size)
            
            // Build actual file name
            val actualFileName = buildFileName(pathInfo.baseName, pathInfo.extension, suffix)
            val file = File(uploadDir, "${pathInfo.userId}/$actualFileName")
            
            log.trace("Resolved file path: {}, exists: {}", file.absolutePath, file.exists())

            // Validate file using utility method
            if (validateImageFile(file)) {
                log.debug("File validation passed for: {}", file.name)
                file
            } else {
                log.warn("File validation failed for: {}", file.absolutePath)
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

    // ==================== Private Utility Methods ====================

    /**
     * Parse an image URL into its components
     * @param imageUrl URL in format: /api/v1/images/userId/filename.jpg
     * @return ImagePathInfo or null if invalid format
     */
    private fun parseImageUrl(imageUrl: String): ImagePathInfo? {
        val path = imageUrl.removePrefix(baseUrl).removePrefix("/")
        val pathParts = path.split("/")
        
        if (pathParts.size != 2) {
            log.warn("Invalid path format: {}", path)
            return null
        }
        
        val userId = pathParts[0]
        val fileName = pathParts[1]
        val extension = fileName.substringAfterLast(".", "jpg")
        val baseName = fileName.substringBeforeLast(".")
        
        return ImagePathInfo(userId, fileName, baseName, extension)
    }

    /**
     * Build a file name with the appropriate size suffix
     * @param baseName Base name without extension
     * @param extension File extension
     * @param suffix Size suffix (_thumb or _full)
     * @return Complete filename
     */
    private fun buildFileName(baseName: String, extension: String, suffix: String): String {
        return "${baseName}${suffix}.$extension"
    }

    /**
     * Generate a thumbnail from image data
     * @param imageData Original image bytes
     * @param targetFile File to write thumbnail to
     * @param extension Image format extension
     * @return true if successful, false if failed
     */
    private fun generateThumbnail(imageData: ByteArray, targetFile: File, extension: String): Boolean {
        return try {
            val inputStream = ByteArrayInputStream(imageData)
            Thumbnails.of(inputStream)
                .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                .keepAspectRatio(true)
                .outputFormat(extension)
                .toFile(targetFile)
            inputStream.close()
            true
        } catch (e: Exception) {
            log.warn("Failed to generate thumbnail: {}", targetFile.name, e)
            false
        }
    }

    /**
     * Get the appropriate size suffix based on size parameter
     * @param size Size parameter ("thumb", "thumbnail", "full", "original")
     * @return Suffix constant
     */
    private fun getSizeSuffix(size: String): String {
        return when (size.lowercase()) {
            "thumb", "thumbnail" -> THUMBNAIL_SUFFIX
            "full", "original" -> FULL_SUFFIX
            else -> {
                log.warn("Unknown size parameter: {}, defaulting to full", size)
                FULL_SUFFIX
            }
        }
    }

    /**
     * Validate that a file exists and is within the upload directory (security check)
     * @param file File to validate
     * @return true if valid, false otherwise
     */
    private fun validateImageFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }
        
        // Security check: ensure file is within upload directory
        return try {
            file.canonicalPath.startsWith(uploadDir.canonicalPath)
        } catch (e: Exception) {
            log.error("Error validating file: {}", file.absolutePath, e)
            false
        }
    }

    /**
     * Clean up empty user directory after file deletion
     * @param userDir User directory to check
     */
    private fun cleanupEmptyDirectory(userDir: File) {
        if (userDir.isDirectory && userDir.listFiles()?.isEmpty() == true) {
            userDir.delete()
            log.debug("Cleaned up empty directory: {}", userDir.name)
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
