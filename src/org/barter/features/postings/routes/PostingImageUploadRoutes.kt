package org.barter.features.postings.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.postings.dao.UserPostingDao
import org.barter.features.postings.model.PostingAttributeDto
import org.barter.features.postings.model.UserPostingRequest
import org.barter.features.postings.service.FirebaseStorageService
import org.barter.features.postings.service.ImageStorageService
import org.barter.features.postings.service.LocalFileStorageService
import org.barter.features.profile.dao.UserProfileDaoImpl
import org.barter.features.authentication.utils.verifyRequestSignature
import org.koin.java.KoinJavaComponent.inject
import java.time.Instant

fun Route.postingImageUploadRoutes() {
    val postingDao: UserPostingDao by inject(UserPostingDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val profileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    // Select storage implementation based on environment variable
    // Set IMAGE_STORAGE_TYPE=firebase to use Firebase, defaults to local
    val storageType = System.getenv("IMAGE_STORAGE_TYPE") ?: "local"
    val imageStorage: ImageStorageService = when (storageType.lowercase()) {
        "firebase" -> FirebaseStorageService()
        else -> LocalFileStorageService()
    }

    println("📁 Using image storage: ${imageStorage.javaClass.simpleName}")

    route("/api/v1/postings") {

        // Create posting with image uploads
        post("/with-images") {
            try {
                val multipart = call.receiveMultipart()

                // Collect form fields and files
                var userId: String? = null
                var title: String? = null
                var description: String? = null
                var isOffer: Boolean? = null
                var value: Double? = null
                var expiresAt: Instant? = null
                var attributesJson: String? = null
                var timestamp: String? = null
                var signature: String? = null
                val imageFiles = mutableListOf<Pair<ByteArray, String>>() // (bytes, contentType)

                // Process multipart data
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "userId" -> userId = part.value
                                "title" -> title = part.value
                                "description" -> description = part.value
                                "isOffer" -> isOffer = part.value.toBoolean()
                                "value" -> value = part.value.toDoubleOrNull()
                                "expiresAt" -> expiresAt =
                                    part.value.toLongOrNull()?.let { Instant.ofEpochMilli(it) }

                                "attributes" -> attributesJson = part.value
                                "timestamp" -> timestamp = part.value
                                "signature" -> signature = part.value
                            }
                        }

                        is PartData.FileItem -> {
                            if (part.name == "images") {
                                val bytes = part.streamProvider().readBytes()
                                val contentType = part.contentType?.toString() ?: "image/jpeg"
                                imageFiles.add(bytes to contentType)
                            }
                        }

                        else -> {}
                    }
                    part.dispose()
                }

                // Validate required fields
                if (userId == null || title == null || description == null || isOffer == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Missing required fields: userId, title, description, isOffer")
                    )
                    return@post
                }

                // Verify authentication
                if (timestamp == null || signature == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing authentication")
                    )
                    return@post
                }

                // Verify signature (simplified - you may need to adjust based on your auth mechanism)
                val publicKey = profileDao.getUserPublicKeyById(userId!!)
                if (publicKey == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid user"))
                    return@post
                }

                // Upload images to Firebase Storage
                val imageUrls = mutableListOf<String>()
                imageFiles.forEachIndexed { index, (bytes, contentType) ->
                    try {
                        val url = imageStorage.uploadImage(
                            imageData = bytes,
                            userId = userId!!,
                            fileName = "image_$index.jpg",
                            contentType = contentType
                        )
                        imageUrls.add(url)
                    } catch (e: Exception) {
                        println("Failed to upload image $index: ${e.message}")
                    }
                }

                // Parse attributes if provided
                val attributes = try {
                    attributesJson?.let { Json.decodeFromString<List<PostingAttributeDto>>(it) }
                } catch (e: Exception) {
                    null
                }

                // Create posting request
                val postingRequest = UserPostingRequest(
                    title = title!!,
                    description = description!!,
                    isOffer = isOffer!!,
                    value = value,
                    expiresAt = expiresAt,
                    imageUrls = imageUrls,
                    attributes = attributes ?: emptyList()
                )

                // Create posting
                val posting = postingDao.createPosting(userId!!, postingRequest)

                if (posting != null) {
                    call.respond(HttpStatusCode.Created, posting)
                } else {
                    // Cleanup uploaded images on failure
                    imageStorage.deleteImages(imageUrls)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create posting")
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "An error occurred: ${e.message}")
                )
            }
        }

        // Update posting with new images
        put("/{postingId}/images") {
            val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
            if (authenticatedUserId == null) {
                return@put
            }

            val postingId = call.parameters["postingId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing postingId"))
                return@put
            }

            try {
                val multipart = call.receiveMultipart()
                val imageFiles = mutableListOf<Pair<ByteArray, String>>()

                multipart.forEachPart { part ->
                    if (part is PartData.FileItem && part.name == "images") {
                        val bytes = part.streamProvider().readBytes()
                        val contentType = part.contentType?.toString() ?: "image/jpeg"
                        imageFiles.add(bytes to contentType)
                    }
                    part.dispose()
                }

                // Upload new images
                val imageUrls = mutableListOf<String>()
                imageFiles.forEachIndexed { index, (bytes, contentType) ->
                    try {
                        val url = imageStorage.uploadImage(
                            imageData = bytes,
                            userId = authenticatedUserId,
                            fileName = "image_${System.currentTimeMillis()}_$index.jpg",
                            contentType = contentType
                        )
                        imageUrls.add(url)
                    } catch (e: Exception) {
                        println("Failed to upload image $index: ${e.message}")
                    }
                }

                // Get existing posting to merge image URLs
                val existingPosting = postingDao.getPosting(postingId)
                if (existingPosting == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Posting not found"))
                    return@put
                }

                // Merge with existing images
                val allImageUrls = existingPosting.imageUrls + imageUrls

                // Update posting
                val updateRequest = UserPostingRequest(
                    title = existingPosting.title,
                    description = existingPosting.description,
                    isOffer = existingPosting.isOffer,
                    value = existingPosting.value,
                    expiresAt = existingPosting.expiresAt,
                    imageUrls = allImageUrls,
                    attributes = existingPosting.attributes
                )

                val success =
                    postingDao.updatePosting(authenticatedUserId, postingId, updateRequest)

                if (success) {
                    val updatedPosting = postingDao.getPosting(postingId)
                    call.respond(
                        HttpStatusCode.OK,
                        updatedPosting ?: mapOf("message" to "Images uploaded")
                    )
                } else {
                    // Cleanup uploaded images on failure
                    imageStorage.deleteImages(imageUrls)
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                }

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "An error occurred: ${e.message}")
                )
            }
        }

        // Delete specific images from a posting
        delete("/{postingId}/images") {
            val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
            if (authenticatedUserId == null) {
                return@delete
            }

            val postingId = call.parameters["postingId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing postingId"))
                return@delete
            }

            try {
                // Request body contains list of image URLs to delete
                val imagesToDelete = Json.decodeFromString<List<String>>(requestBody!!)

                val existingPosting = postingDao.getPosting(postingId)
                if (existingPosting == null || existingPosting.userId != authenticatedUserId) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                    return@delete
                }

                // Delete images from Firebase
                imageStorage.deleteImages(imagesToDelete)

                // Update posting with remaining images
                val remainingImages = existingPosting.imageUrls.filter { it !in imagesToDelete }

                val updateRequest = UserPostingRequest(
                    title = existingPosting.title,
                    description = existingPosting.description,
                    isOffer = existingPosting.isOffer,
                    value = existingPosting.value,
                    expiresAt = existingPosting.expiresAt,
                    imageUrls = remainingImages,
                    attributes = existingPosting.attributes
                )

                postingDao.updatePosting(authenticatedUserId, postingId, updateRequest)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf("message" to "Images deleted", "deletedCount" to imagesToDelete.size)
                )

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "An error occurred: ${e.message}")
                )
            }
        }
    }
}
