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

        // Serve image file
        get("/{userId}/{fileName}") {
            val userId = call.parameters["userId"]
            val fileName = call.parameters["fileName"]

            log.debug("Image request: userId={}, fileName={}", userId, fileName)

            if (userId == null || fileName == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid image URL")
                return@get
            }

            val imageUrl = "/api/v1/images/$userId/$fileName"

            val file = localStorage.getFile(imageUrl)
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
            call.response.headers.append("Cache-Control", "public, max-age=31536000") // 1 year
            call.response.headers.append("ETag", file.name)

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
