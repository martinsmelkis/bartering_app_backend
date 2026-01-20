package app.bartering.features.notifications.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.bartering.extensions.normalizeAttributeForDBProcessing
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.*
import org.koin.java.KoinJavaComponent.inject

/**
 * Batch request data classes for attribute preferences
 */
@Serializable
data class AttributeBatchRequest(
    val attributeIds: List<String>,
    val preferences: AttributeBatchPreferences
)

@Serializable
data class AttributeBatchPreferences(
    val notificationsEnabled: Boolean = true,
    val notificationFrequency: NotificationFrequency = NotificationFrequency.INSTANT,
    val minMatchScore: Double = 0.7,
    val notifyOnNewPostings: Boolean = true,
    val notifyOnNewUsers: Boolean = false
)

/**
 * Routes for managing notification preferences
 */
fun Application.notificationPreferencesRoutes() {
    val preferencesDao: NotificationPreferencesDao by inject(NotificationPreferencesDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    
    routing {
        route("/api/v1/notifications") {
            
            // ============ User Notification Contacts ============
            
            // Get user's notification contacts
            get("/contacts") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@get
                
                val contacts = preferencesDao.getUserContacts(authenticatedUserId)
                if (contacts != null) {
                    call.respond(HttpStatusCode.OK, UserContactsResponse(contacts))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(
                        success = false,
                        message = "No contacts found"
                    ))
                }
            }
            
            // Update user's notification contacts
            put("/contacts") {
                val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null || requestBody == null) return@put
                
                try {
                    val request = Json.decodeFromString<UpdateUserNotificationContactsRequest>(requestBody)
                    val updated = preferencesDao.updateUserContacts(authenticatedUserId, request)
                    
                    if (updated != null) {
                        call.respond(HttpStatusCode.OK, UserContactsResponse(updated))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, NotificationPreferencesResponse(
                            success = false,
                            message = "Failed to update contacts"
                        ))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Invalid request format: ${e.message}"
                    ))
                }
            }
            
            // Add push token
            post("/contacts/push-tokens") {
                val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null || requestBody == null) return@post
                
                try {
                    val request = Json.decodeFromString<AddPushTokenRequest>(requestBody)
                    val tokenInfo = PushTokenInfo(
                        token = request.token,
                        platform = request.platform,
                        deviceId = request.deviceId
                    )
                    
                    val success = preferencesDao.addPushToken(authenticatedUserId, tokenInfo)
                    
                    if (success) {
                        call.respond(HttpStatusCode.OK, NotificationPreferencesResponse(true, "Push token added"))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, NotificationPreferencesResponse(false, "Failed to add push token"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Invalid request format: ${e.message}"
                    ))
                }
            }
            
            // Remove push token
            delete("/contacts/push-tokens/{token}") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@delete
                
                val token = call.parameters["token"]
                if (token.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Token parameter required"
                    ))
                    return@delete
                }
                
                val success = preferencesDao.removePushToken(authenticatedUserId, token)
                
                if (success) {
                    call.respond(HttpStatusCode.OK, NotificationPreferencesResponse(true, "Push token removed"))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(false, "Token not found"))
                }
            }
            
            // ============ Attribute Notification Preferences ============
            
            // Get all attribute preferences for user
            get("/attributes") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@get
                
                val preferences = preferencesDao.getAllAttributePreferences(authenticatedUserId)
                call.respond(HttpStatusCode.OK, AttributePreferencesListResponse(
                    preferences = preferences,
                    totalCount = preferences.size
                ))
            }
            
            // Get specific attribute preference
            get("/attributes/{attributeId}") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@get
                
                val attributeId = call.parameters["attributeId"]?.normalizeAttributeForDBProcessing()
                if (attributeId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Attribute ID required"
                    ))
                    return@get
                }
                
                val preference = preferencesDao.getAttributePreference(authenticatedUserId, attributeId)
                if (preference != null) {
                    call.respond(HttpStatusCode.OK, AttributePreferenceResponse(preference))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(
                        success = false,
                        message = "Preference not found"
                    ))
                }
            }
            
            // Create/update preference for specific attribute
            put("/attributes/{attributeId}") {
                val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null || requestBody == null) return@put
                
                val attributeId = call.parameters["attributeId"]?.normalizeAttributeForDBProcessing()
                if (attributeId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Attribute ID required"
                    ))
                    return@put
                }
                
                try {
                    // Try to parse as update request first
                    val updateRequest = Json.decodeFromString<UpdateAttributeNotificationPreferenceRequest>(requestBody)
                    
                    // Check if preference exists
                    val existing = preferencesDao.getAttributePreference(authenticatedUserId, attributeId)
                    
                    if (existing != null) {
                        // Update existing
                        val updated = preferencesDao.updateAttributePreference(authenticatedUserId, attributeId, updateRequest)
                        call.respond(HttpStatusCode.OK, AttributePreferenceResponse(updated!!))
                    } else {
                        // Create new - need full create request
                        val createRequest = CreateAttributeNotificationPreferenceRequest(
                            attributeId = attributeId.normalizeAttributeForDBProcessing(),
                            notificationsEnabled = updateRequest.notificationsEnabled ?: true,
                            notificationFrequency = updateRequest.notificationFrequency ?: NotificationFrequency.INSTANT,
                            minMatchScore = updateRequest.minMatchScore ?: 0.7,
                            notifyOnNewPostings = updateRequest.notifyOnNewPostings ?: true,
                            notifyOnNewUsers = updateRequest.notifyOnNewUsers ?: false
                        )
                        val created = preferencesDao.saveAttributePreference(authenticatedUserId, createRequest)
                        call.respond(HttpStatusCode.Created, AttributePreferenceResponse(created))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Invalid request format: ${e.message}"
                    ))
                }
            }
            
            // Delete attribute preference
            delete("/attributes/{attributeId}") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@delete
                
                val attributeId = call.parameters["attributeId"]?.normalizeAttributeForDBProcessing()
                if (attributeId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Attribute ID required"
                    ))
                    return@delete
                }
                
                val success = preferencesDao.deleteAttributePreference(authenticatedUserId, attributeId)
                
                if (success) {
                    call.respond(HttpStatusCode.OK, NotificationPreferencesResponse(true, "Preference deleted"))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(false, "Preference not found"))
                }
            }
            
            // Batch update (enable notifications for multiple attributes at once)
            post("/attributes/batch") {
                val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null || requestBody == null) return@post
                
                try {
                    // Parse batch request
                    val batchRequest = Json.decodeFromString<AttributeBatchRequest>(requestBody)
                    
                    val created = mutableListOf<AttributeNotificationPreference>()

                    for (attributeId in batchRequest.attributeIds) {
                        val request = CreateAttributeNotificationPreferenceRequest(
                            attributeId = attributeId.normalizeAttributeForDBProcessing(),
                            notificationsEnabled = batchRequest.preferences.notificationsEnabled,
                            notificationFrequency = batchRequest.preferences.notificationFrequency,
                            minMatchScore = batchRequest.preferences.minMatchScore,
                            notifyOnNewPostings = batchRequest.preferences.notifyOnNewPostings,
                            notifyOnNewUsers = batchRequest.preferences.notifyOnNewUsers
                        )
                        
                        // Only create if doesn't exist
                        val existing = preferencesDao.getAttributePreference(authenticatedUserId, attributeId)
                        if (existing == null) {
                            created.add(preferencesDao.saveAttributePreference(authenticatedUserId, request))
                        }
                    }
                    
                    call.respond(HttpStatusCode.Created, BatchAttributePreferencesResponse(
                        success = true,
                        created = created.size,
                        skipped = batchRequest.attributeIds.size - created.size,
                        preferences = created
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Invalid request format: ${e.message}"
                    ))
                }
            }
            
            // ============ Posting Notification Preferences ============
            
            // Get preference for specific posting
            get("/postings/{postingId}") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@get
                
                val postingId = call.parameters["postingId"]
                if (postingId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Posting ID required"
                    ))
                    return@get
                }
                
                val preference = preferencesDao.getPostingPreference(postingId)
                if (preference != null) {
                    call.respond(HttpStatusCode.OK, PostingPreferenceResponse(preference))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(
                        success = false,
                        message = "Preference not found"
                    ))
                }
            }
            
            // Create/update preference for posting
            put("/postings/{postingId}") {
                val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null || requestBody == null) return@put
                
                val postingId = call.parameters["postingId"]
                if (postingId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Posting ID required"
                    ))
                    return@put
                }
                
                try {
                    val request = Json.decodeFromString<UpdatePostingNotificationPreferenceRequest>(requestBody)
                    
                    val existing = preferencesDao.getPostingPreference(postingId)
                    
                    if (existing != null) {
                        val updated = preferencesDao.updatePostingPreference(postingId, request)
                        call.respond(HttpStatusCode.OK, PostingPreferenceResponse(updated!!))
                    } else {
                        val created = preferencesDao.savePostingPreference(postingId, request)
                        call.respond(HttpStatusCode.Created, PostingPreferenceResponse(created))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Invalid request format: ${e.message}"
                    ))
                }
            }
            
            // Delete posting preference
            delete("/postings/{postingId}") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@delete
                
                val postingId = call.parameters["postingId"]
                if (postingId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Posting ID required"
                    ))
                    return@delete
                }
                
                val success = preferencesDao.deletePostingPreference(postingId)
                
                if (success) {
                    call.respond(HttpStatusCode.OK, NotificationPreferencesResponse(true, "Preference deleted"))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(false, "Preference not found"))
                }
            }
            
            // ============ Match History ============
            
            // Get user's match history
            get("/matches") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@get
                
                val unviewedOnly = call.request.queryParameters["unviewedOnly"]?.toBoolean() ?: false
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val enriched = call.request.queryParameters["enriched"]?.toBoolean() ?: false
                
                if (enriched) {
                    // Return enriched matches with posting/user details
                    val matches = preferencesDao.getUserMatches(authenticatedUserId, unviewedOnly, limit)
                    val unviewedCount = if (unviewedOnly) matches.size else 
                        preferencesDao.getUserMatches(authenticatedUserId, true, Int.MAX_VALUE).size
                    
                    // Enrich matches with posting details
                    val postingDao by inject<app.bartering.features.postings.dao.UserPostingDao>(
                        app.bartering.features.postings.dao.UserPostingDao::class.java
                    )
                    
                    val enrichedMatches = matches.map { match ->
                        var postingUserId: String? = null
                        var postingTitle: String? = null
                        var postingDescription: String? = null
                        var postingImageUrl: String? = null
                        
                        // If target is a posting, fetch details
                        if (match.targetType == TargetType.POSTING) {
                            try {
                                val posting = postingDao.getPosting(match.targetId)
                                if (posting != null) {
                                    postingUserId = posting.userId
                                    postingTitle = posting.title
                                    postingDescription = posting.description.take(200) // Snippet
                                    postingImageUrl = posting.imageUrls.firstOrNull()
                                }
                            } catch (e: Exception) {
                                println("⚠️ Failed to enrich match ${match.id}: ${e.message}")
                            }
                        }
                        
                        EnrichedMatchHistoryEntry(
                            match = match,
                            postingUserId = postingUserId,
                            postingTitle = postingTitle,
                            postingDescription = postingDescription,
                            postingImageUrl = postingImageUrl,
                            targetUserId = if (match.targetType == TargetType.USER) match.targetId else null
                        )
                    }
                    
                    call.respond(HttpStatusCode.OK, EnrichedMatchHistoryResponse(
                        matches = enrichedMatches,
                        totalCount = enrichedMatches.size,
                        unviewedCount = unviewedCount
                    ))
                } else {
                    // Return basic matches
                    val matches = preferencesDao.getUserMatches(authenticatedUserId, unviewedOnly, limit)
                    val unviewedCount = if (unviewedOnly) matches.size else 
                        preferencesDao.getUserMatches(authenticatedUserId, true, Int.MAX_VALUE).size
                    
                    call.respond(HttpStatusCode.OK, MatchHistoryResponse(
                        matches = matches,
                        totalCount = matches.size,
                        unviewedCount = unviewedCount
                    ))
                }
            }
            
            // Mark match as viewed
            post("/matches/{matchId}/viewed") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@post
                
                val matchId = call.parameters["matchId"]
                if (matchId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Match ID required"))
                    return@post
                }
                
                val success = preferencesDao.markMatchViewed(matchId)
                
                if (success) {
                    call.respond(HttpStatusCode.OK, NotificationPreferencesResponse(true, "Match marked as viewed"))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(false, "Match not found"))
                }
            }
            
            // Dismiss match
            post("/matches/{matchId}/dismiss") {
                val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                if (authenticatedUserId == null) return@post
                
                val matchId = call.parameters["matchId"]
                if (matchId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, NotificationPreferencesResponse(
                        success = false,
                        message = "Match ID required"
                    ))
                    return@post
                }
                
                val success = preferencesDao.markMatchDismissed(matchId)
                
                if (success) {
                    call.respond(HttpStatusCode.OK, NotificationPreferencesResponse(true, "Match dismissed"))
                } else {
                    call.respond(HttpStatusCode.NotFound, NotificationPreferencesResponse(false, "Match not found"))
                }
            }
        }
    }

}