package app.bartering.features.postings.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import app.bartering.features.postings.service.LocalFileStorageService
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ImageServeRoutes")

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

            if (userId == null || fileName == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid image URL")
                return@get
            }

            // Validate size parameter
            val validSizes = setOf("thumb", "thumbnail", "full", "original")
            if (size.lowercase() !in validSizes) {
                call.respond(HttpStatusCode.BadRequest, 
                    "Invalid size parameter. Use 'thumb' or 'full'")
                return@get
            }

            val imageUrl = "/api/v1/images/$userId/$fileName"

            val file = localStorage.getFile(imageUrl, size)
            log.debug("Resolved file: {}, exists: {}", file?.absolutePath, file?.exists())

            if (file == null || !file.exists()) {
                call.respond(HttpStatusCode.NotFound, "Image not found")
                return@get
            }

            // Determine content type from extension
            /*val contentType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "png" -> ContentType.Image.PNG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                else -> ContentType.Application.OctetStream
            }*/

            // Set cache headers for better performance
            // Different cache times for thumbnails vs full images
            val maxAge = if (size in setOf("thumb", "thumbnail")) {
                31536000 // 1 year for thumbnails
            } else {
                2592000 // 30 days for full images
            }
            
            call.response.headers.append("Cache-Control", "public, max-age=$maxAge")
            call.response.headers.append("ETag", "${file.name}-$size")
            call.response.headers.append("Vary", "Accept-Encoding")

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
