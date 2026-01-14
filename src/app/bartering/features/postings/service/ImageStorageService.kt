package app.bartering.features.postings.service

/**
 * Interface for image storage implementations.
 * Allows easy switching between local file storage, Firebase, S3, etc.
 */
interface ImageStorageService {

    /**
     * Upload an image
     *
     * @param imageData The image bytes
     * @param userId The user ID (for folder organization)
     * @param fileName The original filename
     * @param contentType The MIME type (e.g., "image/jpeg")
     * @return The URL to access the image
     */
    suspend fun uploadImage(
        imageData: ByteArray,
        userId: String,
        fileName: String,
        contentType: String
    ): String

    /**
     * Delete an image
     *
     * @param imageUrl The URL of the image to delete
     * @return true if deletion was successful
     */
    suspend fun deleteImage(imageUrl: String): Boolean

    /**
     * Delete multiple images
     *
     * @param imageUrls List of image URLs to delete
     * @return Number of successfully deleted images
     */
    suspend fun deleteImages(imageUrls: List<String>): Int {
        var deletedCount = 0
        imageUrls.forEach { url ->
            if (deleteImage(url)) {
                deletedCount++
            }
        }
        return deletedCount
    }

    /**
     * Check if this storage service is properly initialized
     */
    fun isInitialized(): Boolean
}
