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
 * Check if client supports WebP format by examining Accept header
 * @param acceptHeader The Accept header value
 * @return true if WebP is supported
 */
private fun supportsWebP(acceptHeader: String?): Boolean {
    return acceptHeader?.contains("image/webp", ignoreCase = true) ?: false
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
        // Automatically serves WebP if client supports it (via Accept header)
        get("/{userId}/{fileName}") {
            val userId = call.parameters["userId"]
            val fileName = call.parameters["fileName"]
            val rawSize = call.request.queryParameters["size"]
            val size = if (rawSize.isNullOrBlank()) "full" else rawSize

            // Check if client supports WebP
            val acceptHeader = call.request.headers["Accept"]
            val clientSupportsWebP = supportsWebP(acceptHeader)
            
            log.info("Image request: userId={}, fileName={}, requestedSize={}, resolvedSize={}, webpSupport={}", 
                userId, fileName, rawSize, size, clientSupportsWebP)

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
            
            // Try WebP first if client supports it
            var file: File? = null
            var formatServed = "jpeg"
            
            if (clientSupportsWebP) {
                file = localStorage.getFile(imageUrl, size, "webp")
                if (file?.exists() == true) {
                    formatServed = "webp"
                    log.info("Serving WebP version ({}% smaller)", 30)
                } else {
                    log.debug("WebP version not available, falling back to JPEG")
                }
            }
            
            // Fallback to JPEG if WebP not available or not supported
            if (file == null || !file.exists()) {
                file = localStorage.getFile(imageUrl, size, "jpeg")
                formatServed = "jpeg"
            }
            
            log.debug("Resolved file: {}, exists: {}, format: {}", file?.absolutePath, file?.exists(), formatServed)

            if (file == null || !file.exists()) {
                call.respond(HttpStatusCode.NotFound, "Image not found")
                return@get
            }

            // Set response headers using utility functions
            val maxAge = getCacheMaxAge(size)
            call.response.headers.append("Cache-Control", "public, max-age=$maxAge")
            call.response.headers.append("ETag", "${file.name}-$size")
            call.response.headers.append("Vary", "Accept, Accept-Encoding") // Important: Vary on Accept for WebP
            call.response.headers.append("Content-Type", if (formatServed == "webp") "image/webp" else "image/jpeg")

            // Respond with file
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
