package app.bartering.features.postings.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import app.bartering.features.postings.service.LocalFileStorageService
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("ImageServeRoutes")

// ==================== Constants ====================

private val VALID_SIZES = setOf("thumb", "thumbnail", "full", "original")
private val THUMBNAIL_SIZES = setOf("thumb", "thumbnail")
private const val THUMBNAIL_CACHE_MAX_AGE = 31536000 // 1 year
private const val FULL_IMAGE_CACHE_MAX_AGE = 2592000 // 30 days

// ==================== Utility Functions ====================

/**
 * Validate size parameter
 * @return true if valid, false otherwise
 */
private fun isValidSize(size: String): Boolean {
    return size.lowercase() in VALID_SIZES
}

/**
 * Determine appropriate cache max-age based on image size
 * @param size Size parameter ("thumb" or "full")
 * @return Max-age in seconds
 */
private fun getCacheMaxAge(size: String): Int {
    return if (size in THUMBNAIL_SIZES) {
        THUMBNAIL_CACHE_MAX_AGE
    } else {
        FULL_IMAGE_CACHE_MAX_AGE
    }
}

/**
 * Determine content type from file extension
 * @param file File to check
 * @return ContentType
 */
private fun getImageContentType(file: File): ContentType {
    return when (file.extension.lowercase()) {
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "png" -> ContentType.Image.PNG
        "gif" -> ContentType.Image.GIF
        "webp" -> ContentType("image", "webp")
        "bmp" -> ContentType.Image.Any
        "svg" -> ContentType("image", "svg+xml")
        else -> ContentType.Application.OctetStream
    }
}

/**
 * Routes for serving locally stored images.
 * Only needed when using local file storage.
 */
fun Route.imageServeRoutes() {
    val localStorage = LocalFileStorageService()

    route("/api/v1/images") {

        // Serve image file with optional size parameter
        // Supports ?size=thumb or ?size=full (default: full)
        get("/{userId}/{fileName}") {
            val userId = call.parameters["userId"]
            val fileName = call.parameters["fileName"]
            val size = call.request.queryParameters["size"] ?: "full"

            log.debug("Image request: userId={}, fileName={}, size={}", userId, fileName, size)

            // Validate path parameters
            if (userId == null || fileName == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid image URL")
                return@get
            }

            // Validate size parameter using utility function
            if (!isValidSize(size)) {
                call.respond(HttpStatusCode.BadRequest, 
                    "Invalid size parameter. Valid values: ${VALID_SIZES.joinToString(", ")}")
                return@get
            }

            // Retrieve file from storage
            val imageUrl = "/api/v1/images/$userId/$fileName"
            val file = localStorage.getFile(imageUrl, size)
            
            log.debug("Resolved file: {}, exists: {}", file?.absolutePath, file?.exists())

            if (file == null || !file.exists()) {
                call.respond(HttpStatusCode.NotFound, "Image not found")
                return@get
            }

            // Set response headers using utility functions
            val maxAge = getCacheMaxAge(size)
            call.response.headers.append("Cache-Control", "public, max-age=$maxAge")
            call.response.headers.append("ETag", "${file.name}-$size")
            call.response.headers.append("Vary", "Accept-Encoding")

            // Respond with file (Ktor automatically sets Content-Type based on extension)
            call.respondFile(file)
        }

        // Storage statistics endpoint (admin/debug only)
        get("/stats") {
            val stats = localStorage.getStats()
            call.respond(
                HttpStatusCode.OK, mapOf(
                    "totalFiles" to stats.totalFiles,
                    "totalSizeMB" to stats.totalSizeMB,
                    "uploadDirectory" to stats.uploadDirectory
                )
            )
        }
    }
}
