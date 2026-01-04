package org.barter.features.postings.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.postings.dao.UserPostingDao
import org.barter.features.postings.model.UserPostingRequest
import org.barter.features.postings.service.FirebaseStorageService
import org.barter.features.postings.service.ImageStorageService
import org.barter.features.postings.service.LocalFileStorageService
import org.barter.features.authentication.utils.verifyRequestSignature
import org.barter.features.notifications.service.MatchNotificationService
import org.koin.java.KoinJavaComponent.inject
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

fun Route.postingsRoutes() {
    val postingDao: UserPostingDao by inject(UserPostingDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    // Select storage implementation based on environment variable
    val storageType = System.getenv("IMAGE_STORAGE_TYPE") ?: "local"
    val imageStorage: ImageStorageService = when (storageType.lowercase()) {
        "firebase" -> FirebaseStorageService()
        else -> LocalFileStorageService()
    }

    println("📁 Postings using image storage: ${imageStorage.javaClass.simpleName}")

    route("/api/v1/postings") {

        // Create a new posting (with multipart support for images)
        post {
            try {
                val contentType = call.request.contentType()

                // Handle multipart/form-data (with images)
                if (contentType.match(ContentType.MultiPart.FormData)) {
                    val multipart = call.receiveMultipart()

                    // Collect form fields and files
                    var userId: String? = null
                    var title: String? = null
                    var description: String? = null
                    var isOffer: Boolean? = null
                    var value: Double? = null
                    var expiresAt: Instant? = null
                    val imageFiles =
                        mutableListOf<Pair<ByteArray, String>>() // (bytes, contentType)

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
                                    "expiresAt" -> expiresAt = part.value.toLongOrNull()?.let {
                                        Instant.ofEpochMilli(it)
                                    }

                                    "createdAt" -> { /* Ignore, we use server time */
                                    }
                                }
                            }

                            is PartData.FileItem -> {
                                if (part.name == "images") {
                                    val bytes = part.provider().toByteArray()
                                    val contentType = part.contentType?.toString() ?: "image/jpeg"

                                    // Validate file size (10MB limit)
                                    if (bytes.size <= 10 * 1024 * 1024) {
                                        imageFiles.add(bytes to contentType)
                                    } else {
                                        println("⚠️  Image too large: ${bytes.size / 1024 / 1024}MB (max 10MB)")
                                    }
                                }
                            }

                            else -> {}
                        }
                        part.dispose()
                    }

                    // Log received data
                    println("📥 Received posting data: userId=$userId, title=$title, desc length=${description?.length}, isOffer=$isOffer, images=${imageFiles.size}")

                    // Validate required fields
                    if (userId.isNullOrBlank() || title.isNullOrBlank() ||
                        description.isNullOrBlank() || isOffer == null
                    ) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Missing required fields: userId, title, description, isOffer")
                        )
                        return@post
                    }

                    // Upload images to storage
                    val imageUrls = mutableListOf<String>()
                    imageFiles.forEachIndexed { index, (bytes, contentType) ->
                        try {
                            val url = imageStorage.uploadImage(
                                imageData = bytes,
                                userId = userId,
                                fileName = "image_${System.currentTimeMillis()}_$index.jpg",
                                contentType = contentType
                            )
                            imageUrls.add(url)
                        } catch (e: Exception) {
                            println("Failed to upload image $index: ${e.message}")
                            // Continue with other images
                        }
                    }

                    // Create posting request
                    val postingRequest = UserPostingRequest(
                        title = title,
                        description = description,
                        isOffer = isOffer,
                        value = value,
                        expiresAt = expiresAt,
                        imageUrls = imageUrls,
                        attributes = emptyList()
                    )

                    println("📝 Creating posting: userId=$userId, title=$title, imageCount=${imageUrls.size}")

                    // Create posting
                    try {
                        val id = postingDao.createPosting(userId, postingRequest)

                        val posting = postingDao.getPosting(id!!)
                        if (posting != null) {
                            println("✅ Posting created successfully: ${posting.id}")
                            call.respond(HttpStatusCode.Created, posting)

                            // Run matching in background to not block response
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    // Create default posting notification preference (enabled by default)
                                    val preferencesDao: org.barter.features.notifications.dao.NotificationPreferencesDao by inject(
                                        org.barter.features.notifications.dao.NotificationPreferencesDao::class.java
                                    )
                                    
                                    // Check if preference already exists
                                    val existingPref = preferencesDao.getPostingPreference(posting.id)
                                    if (existingPref == null) {
                                        // Create with defaults: enabled, instant notifications
                                        val defaultRequest = org.barter.features.notifications.model.UpdatePostingNotificationPreferenceRequest(
                                            notificationsEnabled = true,
                                            notificationFrequency = org.barter.features.notifications.model.NotificationFrequency.INSTANT,
                                            minMatchScore = 0.7
                                        )
                                        preferencesDao.savePostingPreference(posting.id, defaultRequest)
                                        println("✅ Created default notification preference for posting ${posting.id}")
                                    }
                                    
                                    val matchNotificationService: MatchNotificationService by inject(MatchNotificationService::class.java)
                                    val attributeMatches = matchNotificationService.checkPostingAgainstUserAttributes(posting)
                                    println("🔔 Found ${attributeMatches.size} attribute matches for posting ${posting.id}")
                                    
                                    // If this is an offer, check against interest postings
                                    if (posting.isOffer) {
                                        val postingMatches = matchNotificationService.checkPostingAgainstInterestPostings(posting)
                                        println("🔔 Found ${postingMatches.size} posting matches for posting ${posting.id}")
                                    }
                                } catch (e: Exception) {
                                    println("❌ Error during match checking: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        } else {
                            println("❌ Posting creation returned null")
                            // Cleanup uploaded images on failure
                            imageStorage.deleteImages(imageUrls)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to "Failed to create posting - DAO returned null")
                            )
                        }
                    } catch (e: Exception) {
                        println("❌ Exception during posting creation: ${e.message}")
                        e.printStackTrace()
                        // Cleanup uploaded images on failure
                        imageStorage.deleteImages(imageUrls)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf(
                                "error" to "Failed to create posting in database",
                                "message" to (e.message ?: "Unknown error")
                            )
                        )
                    }

                } else {
                    // Handle JSON (without images) - original behavior
                    val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                    if (authenticatedUserId == null || requestBody == null) {
                        return@post
                    }

                    val request = Json.decodeFromString<UserPostingRequest>(requestBody)
                    val postingId = postingDao.createPosting(authenticatedUserId, request)

                    if (postingId != null) {
                        val posting = postingDao.getPosting(postingId)
                        call.respond(HttpStatusCode.Created, posting ?: mapOf("id" to postingId))
                        
                        // Run matching and create notification preference in background
                        if (posting != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    // Create default posting notification preference (enabled by default)
                                    val preferencesDao: org.barter.features.notifications.dao.NotificationPreferencesDao by inject(
                                        org.barter.features.notifications.dao.NotificationPreferencesDao::class.java
                                    )
                                    
                                    val existingPref = preferencesDao.getPostingPreference(posting.id)
                                    if (existingPref == null) {
                                        val defaultRequest = org.barter.features.notifications.model.UpdatePostingNotificationPreferenceRequest(
                                            notificationsEnabled = true,
                                            notificationFrequency = org.barter.features.notifications.model.NotificationFrequency.INSTANT,
                                            minMatchScore = 0.7
                                        )
                                        preferencesDao.savePostingPreference(posting.id, defaultRequest)
                                        println("✅ Created default notification preference for posting ${posting.id}")
                                    }
                                    
                                    val matchNotificationService: MatchNotificationService by inject(MatchNotificationService::class.java)
                                    val attributeMatches = matchNotificationService.checkPostingAgainstUserAttributes(posting)
                                    println("🔔 Found ${attributeMatches.size} attribute matches for posting ${posting.id}")
                                    
                                    if (posting.isOffer) {
                                        val postingMatches = matchNotificationService.checkPostingAgainstInterestPostings(posting)
                                        println("🔔 Found ${postingMatches.size} posting matches for posting ${posting.id}")
                                    }
                                } catch (e: Exception) {
                                    println("❌ Error during match checking: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to create posting")
                        )
                    }
                }

            } catch (e: Exception) {
                println("❌ Error creating posting: ${e.message}")
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to "An error occurred while creating posting",
                        "message" to (e.message ?: "Unknown error"),
                        "type" to e.javaClass.simpleName
                    )
                )
            }
        }

        // Update a posting (with multipart support for images)
        put("/{postingId}") {
            try {
                val postingId = call.parameters["postingId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing postingId"))
                    return@put
                }

                val contentType = call.request.contentType()

                // Handle multipart/form-data (with images)
                if (contentType.match(ContentType.MultiPart.FormData)) {
                    val multipart = call.receiveMultipart()

                    // Collect form fields and files
                    var userId: String? = null
                    var title: String? = null
                    var description: String? = null
                    var isOffer: Boolean? = null
                    var value: Double? = null
                    var expiresAt: Instant? = null
                    val imageFiles = mutableListOf<Pair<ByteArray, String>>()

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
                                    "expiresAt" -> expiresAt = part.value.toLongOrNull()?.let {
                                        Instant.ofEpochMilli(it)
                                    }
                                }
                            }

                            is PartData.FileItem -> {
                                if (part.name == "images") {
                                    val bytes = part.provider().toByteArray()
                                    val contentType = part.contentType?.toString() ?: "image/jpeg"

                                    // Validate file size (10MB limit)
                                    if (bytes.size <= 10 * 1024 * 1024) {
                                        imageFiles.add(bytes to contentType)
                                    } else {
                                        println("⚠️  Image too large: ${bytes.size / 1024 / 1024}MB (max 10MB)")
                                    }
                                }
                            }

                            else -> {}
                        }
                        part.dispose()
                    }

                    println("📥 Updating posting $postingId: userId=$userId, title=$title, images=${imageFiles.size}")

                    // Validate required fields
                    if (userId.isNullOrBlank() || title.isNullOrBlank() ||
                        description.isNullOrBlank() || isOffer == null
                    ) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Missing required fields: userId, title, description, isOffer")
                        )
                        return@put
                    }

                    // Get existing posting to preserve old images if no new ones uploaded
                    val existingPosting = postingDao.getPosting(postingId)
                    val oldImageUrls = existingPosting?.imageUrls ?: emptyList()

                    // Upload new images if provided
                    val newImageUrls = mutableListOf<String>()
                    if (imageFiles.isNotEmpty()) {
                        imageFiles.forEachIndexed { index, (bytes, contentType) ->
                            try {
                                val url = imageStorage.uploadImage(
                                    imageData = bytes,
                                    userId = userId,
                                    fileName = "image_${System.currentTimeMillis()}_$index.jpg",
                                    contentType = contentType
                                )
                                newImageUrls.add(url)
                            } catch (e: Exception) {
                                println("Failed to upload image $index: ${e.message}")
                            }
                        }
                    }

                    // Use new images if uploaded, otherwise keep old ones
                    val finalImageUrls = newImageUrls.ifEmpty { oldImageUrls }

                    // Create update request
                    val updateRequest = UserPostingRequest(
                        title = title,
                        description = description,
                        isOffer = isOffer,
                        value = value,
                        expiresAt = expiresAt,
                        imageUrls = finalImageUrls,
                        attributes = emptyList()
                    )

                    // Update posting
                    val success = postingDao.updatePosting(userId, postingId, updateRequest)

                    if (success) {
                        // Delete old images if we uploaded new ones
                        if (newImageUrls.isNotEmpty() && oldImageUrls.isNotEmpty()) {
                            try {
                                imageStorage.deleteImages(oldImageUrls)
                            } catch (e: Exception) {
                                println("⚠️  Failed to delete old images: ${e.message}")
                            }
                        }

                        val updatedPosting = postingDao.getPosting(postingId)
                        call.respond(HttpStatusCode.OK, updatedPosting ?: mapOf("message" to "Posting updated"))
                    } else {
                        // Cleanup newly uploaded images on failure
                        if (newImageUrls.isNotEmpty()) {
                            imageStorage.deleteImages(newImageUrls)
                        }
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Posting not found or unauthorized")
                        )
                    }

                } else {
                    // Handle JSON (without images) - signature verification
                    val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                    if (authenticatedUserId == null || requestBody == null) {
                        return@put
                    }

                    val request = Json.decodeFromString<UserPostingRequest>(requestBody)
                    val success = postingDao.updatePosting(authenticatedUserId, postingId, request)

                    if (success) {
                        val updatedPosting = postingDao.getPosting(postingId)
                        call.respond(
                            HttpStatusCode.OK,
                            updatedPosting ?: mapOf("message" to "Posting updated")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Posting not found or unauthorized")
                        )
                    }
                }

            } catch (e: Exception) {
                println("❌ Error updating posting: ${e.message}")
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to "An error occurred while updating posting",
                        "message" to (e.message ?: "Unknown error")
                    )
                )
            }
        }

        // Delete a posting
        delete("/{postingId}") {
            val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
            if (authenticatedUserId == null) {
                return@delete
            }

            val postingId = call.parameters["postingId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing postingId"))
                return@delete
            }

            val success = postingDao.deletePosting(authenticatedUserId, postingId)

            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Posting deleted"))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Posting not found or unauthorized")
                )
            }
        }

        // Get a specific posting
        get("/{postingId}") {
            val postingId = call.parameters["postingId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing postingId"))
                return@get
            }

            val posting = postingDao.getPosting(postingId)

            if (posting != null) {
                call.respond(HttpStatusCode.OK, posting)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Posting not found"))
            }
        }

        // Get user's own postings
        post("/user/me") {
            val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
            if (authenticatedUserId == null) {
                return@post
            }

            val includeExpired =
                call.request.queryParameters["includeExpired"]?.toBoolean() ?: false
            val postings = postingDao.getUserPostings(authenticatedUserId, includeExpired)

            call.respond(HttpStatusCode.OK, postings)
        }

        // Get postings by a specific user
        get("/user/{userId}") {
            val userId = call.parameters["userId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
                return@get
            }

            val postings = postingDao.getUserPostings(userId, includeExpired = false)

            call.respond(HttpStatusCode.OK, postings)
        }

        // Get nearby postings
        get("/nearby") {
            val latitude = call.request.queryParameters["latitude"]?.toDoubleOrNull() ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing or invalid latitude")
                )
                return@get
            }

            val longitude = call.request.queryParameters["longitude"]?.toDoubleOrNull() ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing or invalid longitude")
                )
                return@get
            }

            val radiusMeters =
                call.request.queryParameters["radiusMeters"]?.toDoubleOrNull() ?: 5000.0
            val isOffer = call.request.queryParameters["isOffer"]?.toBoolean()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val excludeUserId = call.request.queryParameters["excludeUserId"]

            val postings = postingDao.getNearbyPostings(
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                isOffer = isOffer,
                excludeUserId = excludeUserId,
                limit = limit
            )

            call.respond(HttpStatusCode.OK, postings)
        }

        // Search postings by keyword
        get("/search") {
            val searchText = call.request.queryParameters["q"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing search query"))
                return@get
            }

            val latitude = call.request.queryParameters["latitude"]?.toDoubleOrNull()
            val longitude = call.request.queryParameters["longitude"]?.toDoubleOrNull()
            val radiusMeters = call.request.queryParameters["radiusMeters"]?.toDoubleOrNull()
            val isOffer = call.request.queryParameters["isOffer"]?.toBoolean()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

            val postings = postingDao.searchPostings(
                searchText = searchText,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                isOffer = isOffer,
                limit = limit
            )

            call.respond(HttpStatusCode.OK, postings)
        }

        // Get matching postings based on user's profile
        post("/matches") {
            val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
            if (authenticatedUserId == null) {
                return@post
            }

            val latitude = call.request.queryParameters["latitude"]?.toDoubleOrNull()
            val longitude = call.request.queryParameters["longitude"]?.toDoubleOrNull()
            val radiusMeters = call.request.queryParameters["radiusMeters"]?.toDoubleOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

            val postings = postingDao.getMatchingPostings(
                userId = authenticatedUserId,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                limit = limit
            )

            call.respond(HttpStatusCode.OK, postings)
        }
    }
}
