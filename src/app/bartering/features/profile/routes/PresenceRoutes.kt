package app.bartering.features.profile.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.profile.cache.UserActivityCache
import org.koin.java.KoinJavaComponent.inject

/**
 * API models for presence/online status
 */
@Serializable
data class OnlineStatusResponse(
    val userId: String,
    val online: Boolean,
    val lastSeenAt: Long?, // Epoch millis, null if never seen
    val lastActivityType: String? = null
)

@Serializable
data class BatchOnlineStatusRequest(
    val userIds: List<String>
)

@Serializable
data class BatchOnlineStatusResponse(
    val statuses: Map<String, UserOnlineStatus>
)

@Serializable
data class UserOnlineStatus(
    val online: Boolean,
    val lastSeenAt: Long?, // Epoch millis
    val lastActivityType: String? = null
)

@Serializable
data class CacheStatsResponse(
    val totalUsers: Int,
    val onlineUsers: Int,
    val dirtyRecords: Int,
    val timestamp: Long
)

/**
 * Route to check if a single user is online
 * 
 * GET /api/v1/users/{userId}/online-status
 */
fun Route.getUserOnlineStatusRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    
    get("/api/v1/users/{userId}/online-status") {
        // Verify authentication
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }
        
        val targetUserId = call.parameters["userId"]
        if (targetUserId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }
        
        try {
            val isOnline = UserActivityCache.isOnline(targetUserId)
            val lastSeen = UserActivityCache.getLastSeen(targetUserId)
            val activityType = UserActivityCache.getLastActivityType(targetUserId)
            
            call.respond(
                HttpStatusCode.OK,
                OnlineStatusResponse(
                    userId = targetUserId,
                    online = isOnline,
                    lastSeenAt = lastSeen,
                    lastActivityType = activityType
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to check online status: ${e.message}")
            )
        }
    }
}

/**
 * Route to check online status for multiple users at once
 * More efficient than individual requests
 * 
 * POST /api/v1/users/online-status/batch
 * Body: { "userIds": ["user1", "user2", "user3"] }
 */
fun Route.batchUserOnlineStatusRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    
    post("/api/v1/users/online-status/batch") {
        // Verify authentication
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@post
        }
        
        try {
            val request = call.receive<BatchOnlineStatusRequest>()
            
            if (request.userIds.isEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "userIds list cannot be empty")
                )
            }
            
            if (request.userIds.size > 100) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Cannot check more than 100 users at once")
                )
            }
            
            // Batch check - very efficient!
            val onlineStatuses = UserActivityCache.getBatchOnlineStatus(request.userIds)
            val lastSeenTimes = UserActivityCache.getBatchLastSeen(request.userIds)
            
            val statuses = request.userIds.associateWith { userId ->
                UserOnlineStatus(
                    online = onlineStatuses[userId] ?: false,
                    lastSeenAt = lastSeenTimes[userId],
                    lastActivityType = UserActivityCache.getLastActivityType(userId)
                )
            }
            
            call.respond(
                HttpStatusCode.OK,
                BatchOnlineStatusResponse(statuses = statuses)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to check batch status: ${e.message}")
            )
        }
    }
}

/**
 * Route to get cache statistics (for monitoring/debugging)
 * 
 * GET /api/v1/admin/presence/stats
 */
fun Route.getPresenceCacheStatsRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    
    get("/api/v1/admin/presence/stats") {
        // Verify authentication
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        try {
            val stats = UserActivityCache.getStats()
            
            call.respond(
                HttpStatusCode.OK,
                CacheStatsResponse(
                    totalUsers = stats.totalUsers,
                    onlineUsers = stats.onlineUsers,
                    dirtyRecords = stats.dirtyRecords,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to get cache stats: ${e.message}")
            )
        }
    }
}
