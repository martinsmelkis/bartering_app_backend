package app.bartering.features.relationships.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.relationships.dao.UserRelationshipsDaoImpl
import app.bartering.features.relationships.model.*
import app.bartering.features.authentication.utils.verifyRequestSignature
import org.koin.java.KoinJavaComponent.inject

/**
 * Create or update a relationship between users
 */
fun Route.createRelationshipRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/relationships/create") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<RelationshipRequest>(requestBody)

            // Verify the authenticated user matches the fromUserId
            if (authenticatedUserId != request.fromUserId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only create relationships for yourself")
                )
            }

            // Validate relationship type
            val relationshipType = RelationshipType.fromString(request.relationshipType)
            if (relationshipType == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid relationship type: ${request.relationshipType}")
                )
            }

            // Don't allow creating relationships with yourself
            if (request.fromUserId == request.toUserId) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Cannot create relationship with yourself")
                )
            }

            // Check if user is trying to interact with someone who blocked them
            if (relationshipType != RelationshipType.BLOCKED) {
                val isBlocked = relationshipsDao.isBlocked(request.toUserId, request.fromUserId)
                if (isBlocked) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Cannot interact with this user")
                    )
                }
            }

            val success = relationshipsDao.createRelationship(
                request.fromUserId,
                request.toUserId,
                relationshipType
            )

            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to create relationship")
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Remove a relationship between users
 */
fun Route.removeRelationshipRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/relationships/remove") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<RemoveRelationshipRequest>(requestBody)

            // Verify the authenticated user matches the fromUserId
            if (authenticatedUserId != request.fromUserId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only remove your own relationships")
                )
            }

            val relationshipType = RelationshipType.fromString(request.relationshipType)
            if (relationshipType == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid relationship type")
                )
            }

            val success = relationshipsDao.removeRelationship(
                request.fromUserId,
                request.toUserId,
                relationshipType
            )

            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Relationship not found")
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Get all relationships for a user
 */
fun Route.getUserRelationshipsRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/relationships/{userId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        // Users can only view their own relationships
        if (authenticatedUserId != userId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only view your own relationships")
            )
        }

        try {
            val relationships = relationshipsDao.getUserRelationships(userId)
            call.respond(HttpStatusCode.OK, relationships)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve relationships")
            )
        }
    }
}

/**
 * Get relationships with profile information
 */
fun Route.getRelationshipsWithProfilesRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/relationships/{userId}/{type}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val userId = call.parameters["userId"]
        val typeParam = call.parameters["type"]

        if (userId.isNullOrBlank() || typeParam.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId or type parameter")
            )
        }

        if (authenticatedUserId != userId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only view your own relationships")
            )
        }

        val relationshipType = RelationshipType.fromString(typeParam)
        if (relationshipType == null) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid relationship type: $typeParam")
            )
        }

        try {
            val relationships =
                relationshipsDao.getRelationshipsWithProfiles(userId, relationshipType)
            call.respond(HttpStatusCode.OK, relationships)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve relationships")
            )
        }
    }
}

/**
 * Accept a friend request
 */
fun Route.acceptFriendRequestRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/relationships/friend-request/accept") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<AcceptFriendRequestRequest>(requestBody)

            if (authenticatedUserId != request.userId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only accept friend requests for yourself")
                )
            }

            val success = relationshipsDao.acceptFriendRequest(request.userId, request.friendUserId)

            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } else {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Friend request not found or already processed")
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Reject a friend request
 */
fun Route.rejectFriendRequestRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/relationships/friend-request/reject") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<AcceptFriendRequestRequest>(requestBody)

            if (authenticatedUserId != request.userId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only reject friend requests for yourself")
                )
            }

            val success = relationshipsDao.rejectFriendRequest(request.userId, request.friendUserId)

            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Friend request not found")
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Get relationship statistics for a user
 */
fun Route.getRelationshipStatsRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/relationships/{userId}/stats") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        // Users can view stats for other users (public information)
        try {
            val stats = relationshipsDao.getRelationshipStats(userId)
            call.respond(HttpStatusCode.OK, stats)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve statistics")
            )
        }
    }
}

/**
 * Check if a relationship exists
 */
fun Route.checkRelationshipRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/relationships/check") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val fromUserId = call.request.queryParameters["fromUserId"]
        val toUserId = call.request.queryParameters["toUserId"]
        val typeParam = call.request.queryParameters["type"]

        if (fromUserId.isNullOrBlank() || toUserId.isNullOrBlank() || typeParam.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing required parameters")
            )
        }

        if (authenticatedUserId != fromUserId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only check your own relationships")
            )
        }

        val relationshipType = RelationshipType.fromString(typeParam)
        if (relationshipType == null) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid relationship type")
            )
        }

        try {
            val exists = relationshipsDao.relationshipExists(fromUserId, toUserId, relationshipType)
            call.respond(HttpStatusCode.OK, mapOf("exists" to exists))

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to check relationship")
            )
        }
    }
}

/**
 * Get full user profiles for all favorited users
 */
fun Route.getFavoritedProfilesRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val profileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    get("/api/v1/relationships/favorites/profiles/{userId}") {
        // Get userId from path parameter since GET requests are not signed
        val userId = call.parameters["userId"]

        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        try {
            println("🔍 Fetching favorited profiles for user: $userId")

            // Get all favorited user IDs
            val favoritedUserIds = relationshipsDao.getRelationshipsByType(
                userId,
                RelationshipType.FAVORITE
            )

            println("🔍 Found ${favoritedUserIds.size} favorited user IDs: $favoritedUserIds")

            // Fetch full profiles for each favorited user
            val profiles = favoritedUserIds.mapNotNull { favUserId ->
                try {
                    println("🔍 Fetching profile for favorited user: $favUserId")
                    val profile = profileDao.getProfile(favUserId)
                    println("🔍 Profile fetched successfully: ${profile?.userId}")
                    profile
                } catch (e: Exception) {
                    println("⚠️  Failed to fetch profile for user $favUserId: ${e.message}")
                    e.printStackTrace()
                    null
                }
            }

            println("🔍 Returning ${profiles.size} profiles")

            call.respond(HttpStatusCode.OK, profiles)

        } catch (e: Exception) {
            println("❌ Error fetching favorited profiles: ${e.message}")
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve favorited profiles")
            )
        }
    }
}
