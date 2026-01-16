package app.bartering.features.relationships.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.relationships.dao.UserRelationshipsDaoImpl
import app.bartering.features.relationships.model.*
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("UserBlockRoutes")

/**
 * Block a user
 */
fun Route.blockUserRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/users/block") {
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
                    mapOf("error" to "You can only block users for yourself")
                )
            }

            // Don't allow blocking yourself
            if (request.fromUserId == request.toUserId) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Cannot block yourself")
                )
            }

            // Create the BLOCKED relationship
            val success = relationshipsDao.createRelationship(
                request.fromUserId,
                request.toUserId,
                RelationshipType.BLOCKED
            )

            if (success) {
                // Remove any friend relationship if exists
                relationshipsDao.removeRelationship(
                    request.fromUserId,
                    request.toUserId,
                    RelationshipType.FRIEND
                )
                relationshipsDao.removeRelationship(
                    request.toUserId,
                    request.fromUserId,
                    RelationshipType.FRIEND
                )

                // Remove any friend request if exists
                relationshipsDao.removeRelationship(
                    request.fromUserId,
                    request.toUserId,
                    RelationshipType.FRIEND_REQUEST_SENT
                )
                relationshipsDao.removeRelationship(
                    request.toUserId,
                    request.fromUserId,
                    RelationshipType.FRIEND_REQUEST_SENT
                )

                call.respond(HttpStatusCode.OK, "success")
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to block user")
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
 * Unblock a user
 */
fun Route.unblockUserRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/users/unblock") {
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
                    mapOf("error" to "You can only unblock users for yourself")
                )
            }

            val success = relationshipsDao.removeRelationship(
                request.fromUserId,
                request.toUserId,
                RelationshipType.BLOCKED
            )

            if (success) {
                call.respond(HttpStatusCode.OK, "success")
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Block relationship not found")
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
 * Check if a user is blocked
 */
fun Route.checkIsBlockedRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/users/isBlocked") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val fromUserId = call.request.queryParameters["fromUserId"]
        val toUserId = call.request.queryParameters["toUserId"]

        if (fromUserId.isNullOrBlank() || toUserId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing required parameters")
            )
        }

        // Users can only check their own block status
        if (authenticatedUserId != fromUserId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only check your own block status")
            )
        }

        try {
            val isBlocked = relationshipsDao.isBlocked(fromUserId, toUserId)
            call.respond(HttpStatusCode.OK, isBlocked)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to check block status")
            )
        }
    }
}

/**
 * Get list of blocked users with profiles
 */
fun Route.getBlockedUsersRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val profileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/users/blocked/{userId}") {
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

        // Users can only view their own blocked list
        if (authenticatedUserId != userId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only view your own blocked users")
            )
        }

        try {
            val blockedUserIds = relationshipsDao.getRelationshipsByType(
                userId,
                RelationshipType.BLOCKED
            )

            // Fetch profiles for blocked users
            val profiles = blockedUserIds.mapNotNull { blockedUserId ->
                try {
                    profileDao.getProfile(blockedUserId)
                } catch (e: Exception) {
                    log.warn("Failed to fetch profile for blocked userId={}", blockedUserId, e)
                    null
                }
            }

            call.respond(HttpStatusCode.OK, profiles)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve blocked users")
            )
        }
    }
}

/**
 * Get list of users who blocked this user (useful for debugging)
 */
fun Route.getBlockedByUsersRoute() {
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/users/blockedBy/{userId}") {
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

        // Users can only view who blocked them
        if (authenticatedUserId != userId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only view users who blocked you")
            )
        }

        try {
            val blockedByUserIds = relationshipsDao.getBlockedByUsers(userId)
            call.respond(HttpStatusCode.OK, blockedByUserIds)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve blocked-by users")
            )
        }
    }
}
