package app.bartering.features.federation.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import app.bartering.features.federation.dao.FederationDao
import app.bartering.features.federation.middleware.verifyAdminAccess
import app.bartering.features.federation.model.*
import app.bartering.features.federation.service.FederationService
import app.bartering.features.authentication.dao.AuthenticationDao
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.profile.cache.UserActivityCache
import org.koin.ktor.ext.inject

/**
 * Federation API routes for server-to-server communication.
 * These endpoints are called by other federated servers, not by client apps.
 */
fun Route.federationRoutes() {

    val federationService by inject<FederationService>()
    val federationDao by inject<FederationDao>()
    val userProfileDao by inject<app.bartering.features.profile.dao.UserProfileDao>()
    val connectionManager by inject<app.bartering.features.chat.manager.ConnectionManager>()
    val offlineMessageDao by inject<app.bartering.features.chat.dao.OfflineMessageDao>()
    val postingDao by inject<app.bartering.features.postings.dao.UserPostingDao>()
    val federatedUserDao by inject<app.bartering.features.federation.dao.FederatedUserDao>()

    route("/federation/v1") {

        /**
         * GET /federation/v1/server-info
         * Returns public information about this server instance.
         * Used by other servers to discover capabilities before handshake.
         */
        get("/server-info") {
            try {
                val serverInfo = federationService.getLocalServerIdentity()
                if (serverInfo != null) {
                    call.respond(HttpStatusCode.OK, FederationApiResponse(
                        success = true,
                        data = serverInfo,
                        error = null,
                        timestamp = System.currentTimeMillis()
                    ))
                } else {
                    call.respond(HttpStatusCode.NotFound, FederationApiResponse(
                        success = false,
                        data = null,
                        error = "Server identity not initialized. Contact server administrator.",
                        timestamp = System.currentTimeMillis()
                    ))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Failed to retrieve server info: ${e.message}",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }

        /**
         * POST /federation/v1/handshake
         * Receives handshake requests from other servers wanting to federate.
         */
        post("/handshake") {
            try {
                val request = call.receive<FederationHandshakeRequest>()

                // Validate required fields
                if (request.serverId.isBlank() || request.serverUrl.isBlank() ||
                    request.publicKey.isBlank() || request.signature.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, FederationApiResponse(
                        success = false,
                        data = null,
                        error = "Missing required fields in handshake request",
                        timestamp = System.currentTimeMillis()
                    ))
                    return@post
                }

                // Accept with default scopes (admin can adjust later)
                val response = federationService.acceptHandshake(
                    request = request,
                    acceptedScopes = FederationScope(
                        users = request.proposedScopes.users,
                        postings = request.proposedScopes.postings,
                        chat = request.proposedScopes.chat,
                        geolocation = false, // Require explicit approval for geolocation
                        attributes = request.proposedScopes.attributes
                    )
                )

                // Return response directly (not wrapped) for server-to-server communication
                call.respond(HttpStatusCode.OK, response)

            } catch (e: SecurityException) {
                call.respond(HttpStatusCode.Unauthorized, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Invalid signature: ${e.message}",
                    timestamp = System.currentTimeMillis()
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Invalid handshake request: ${e.message}",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }

        /**
         * POST /federation/v1/sync-users
         * Receives requests to sync user data from federated servers.
         * Returns paginated list of local users that are federation-enabled.
         */
        post("/sync-users") {
            try {
                val request = call.receive<UserSyncRequest>()

                // Validate federation request with all checks
                val authResult = validateFederationRequest(
                    serverId = request.requestingServerId,
                    requiredScope = ScopeType.USERS,
                    signatureData = "${request.requestingServerId}|${request.timestamp}",
                    signature = request.signature,
                    federationDao = federationDao,
                    federationService = federationService
                )
                
                if (call.handleAuthFailure(authResult)) return@post
                val server = (authResult as FederationAuthResult.Success).server

                // Get users for sync with pagination
                val (users, totalCount) = federatedUserDao.getLocalUsersForSync(
                    page = request.page,
                    pageSize = request.pageSize.coerceAtMost(100), // Cap at 100
                    updatedSince = request.updatedSince
                )

                // Log federation event
                federationService.logFederationEvent(
                    eventType = FederationEventType.USER_SYNC,
                    serverId = request.requestingServerId,
                    action = "user_sync",
                    outcome = FederationOutcome.SUCCESS,
                    details = mapOf(
                        "page" to request.page,
                        "pageSize" to request.pageSize,
                        "updatedSince" to request.updatedSince?.toString(),
                        "usersReturned" to users.size,
                        "totalCount" to totalCount
                    )
                )

                call.respond(HttpStatusCode.OK, FederationApiResponse<UserSyncResponse>(
                    success = true,
                    data = UserSyncResponse(
                        users = users,
                        totalCount = totalCount,
                        page = request.page,
                        hasMore = (request.page + 1) * request.pageSize < totalCount
                    ),
                    error = null,
                    timestamp = System.currentTimeMillis()
                ))

            } catch (e: Exception) {
                e.printStackTrace()
                
                // Log failure
                val serverId = try {
                    call.receive<UserSyncRequest>().requestingServerId
                } catch (e2: Exception) {
                    "unknown"
                }
                
                federationService.logFederationEvent(
                    eventType = FederationEventType.USER_SYNC,
                    serverId = serverId,
                    action = "user_sync",
                    outcome = FederationOutcome.FAILURE,
                    details = null,
                    errorMessage = e.message
                )
                
                call.respond(HttpStatusCode.InternalServerError, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Failed to sync users: ${e.message}",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }

        /**
         * GET /federation/v1/users/nearby
         * Receives geolocation-based user search requests from federated servers.
         * Searches local users by location and returns sanitized profiles.
         */
        get("/users/nearby") {
            val serverId = call.request.queryParameters["serverId"] ?: ""
            val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
            val radius = call.request.queryParameters["radius"]?.toDoubleOrNull() ?: 50.0
            val signature = call.request.queryParameters["signature"] ?: ""
            val timestamp = call.request.queryParameters["timestamp"]?.toLongOrNull()
                ?: System.currentTimeMillis()

            // Require server ID
            if (serverId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Server ID required for geolocation access",
                    timestamp = System.currentTimeMillis()
                ))
                return@get
            }

            // Validate federation request with all checks
            val authResult = validateFederationRequest(
                serverId = serverId,
                requiredScope = ScopeType.GEOLOCATION,
                signatureData = "$serverId|$lat|$lon|$radius|$timestamp",
                signature = signature,
                federationDao = federationDao,
                federationService = federationService
            )
            
            if (call.handleAuthFailure(authResult)) return@get
            val server = (authResult as FederationAuthResult.Success).server

            // Validate coordinates
            if (lat == null || lon == null) {
                call.respond(HttpStatusCode.BadRequest, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Invalid coordinates. Both lat and lon are required.",
                    timestamp = System.currentTimeMillis()
                ))
                return@get
            }

            // Perform geolocation search
            try {
                val nearbyUsers = userProfileDao.getNearbyProfiles(
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radius * 1000, // Convert km to meters
                    excludeUserId = null
                )

                // Convert to federated user profiles
                val federatedProfiles = nearbyUsers.map { userWithDistance ->
                    val profile = userWithDistance.profile
                    
                    // Get last online timestamp from activity cache
                    val lastOnlineTimestamp = try {
                        UserActivityCache.getLastSeen(profile.userId)
                    } catch (e: Exception) {
                        null
                    }
                    
                    FederatedUserProfile(
                        userId = profile.userId,
                        name = profile.name,
                        bio = null, // Not exposing bio in federation for privacy
                        profileImageUrl = null, // Not exposing images for bandwidth
                        location = if (profile.latitude != null && profile.longitude != null) {
                            FederatedLocation(
                                lat = profile.latitude!!,
                                lon = profile.longitude!!,
                                city = null, // Not implemented
                                country = null // Not implemented
                            )
                        } else null,
                        attributes = profile.attributes.map { it.attributeId }, // Just the attribute IDs
                        lastOnline = lastOnlineTimestamp?.let { java.time.Instant.ofEpochMilli(it) }
                    )
                }

                // Log federation event
                federationService.logFederationEvent(
                    eventType = FederationEventType.USER_SEARCH,
                    serverId = serverId,
                    action = "geolocation_search",
                    outcome = FederationOutcome.SUCCESS,
                    details = mapOf(
                        "lat" to lat,
                        "lon" to lon,
                        "radius" to radius,
                        "resultsCount" to federatedProfiles.size
                    )
                )

                call.respond(HttpStatusCode.OK, FederationApiResponse(
                    success = true,
                    data = UserSearchResponse(
                        users = federatedProfiles,
                        count = federatedProfiles.size
                    ),
                    error = null,
                    timestamp = System.currentTimeMillis()
                ))

            } catch (e: Exception) {
                e.printStackTrace()

                federationService.logFederationEvent(
                    eventType = FederationEventType.USER_SEARCH,
                    serverId = serverId,
                    action = "geolocation_search",
                    outcome = FederationOutcome.FAILURE,
                    details = null,
                    errorMessage = e.message
                )
                
                call.respond(HttpStatusCode.InternalServerError, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Failed to perform geolocation search: ${e.message}",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }

        /**
         * POST /federation/v1/messages/relay
         * Receives message relay requests for cross-server chat.
         * Relays encrypted messages from federated servers to local users.
         */
        post("/messages/relay") {
            try {
                val request = call.receive<MessageRelayRequest>()

                // Validate federation request with all checks
                val authResult = validateFederationRequest(
                    serverId = request.requestingServerId,
                    requiredScope = ScopeType.CHAT,
                    signatureData = "${request.requestingServerId}|${request.timestamp}|${request.encryptedPayload}",
                    signature = request.signature,
                    federationDao = federationDao,
                    federationService = federationService
                )
                
                if (call.handleAuthFailure(authResult)) return@post
                val server = (authResult as FederationAuthResult.Success).server

                // Check if recipient user exists on this server
                val recipientProfile = try {
                    userProfileDao.getProfile(request.recipientUserId)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, FederationApiResponse(
                        success = false,
                        data = MessageRelayResponse(
                            delivered = false,
                            messageId = null,
                            reason = "Failed to lookup recipient: ${e.message}"
                        ),
                        error = null,
                        timestamp = System.currentTimeMillis()
                    ))
                    return@post
                }

                if (recipientProfile == null) {
                    call.respond(HttpStatusCode.NotFound, FederationApiResponse(
                        success = false,
                        data = MessageRelayResponse(
                            delivered = false,
                            messageId = null,
                            reason = "Recipient user not found on this server"
                        ),
                        error = null,
                        timestamp = System.currentTimeMillis()
                    ))
                    return@post
                }

                // Try to deliver message in real-time if user is connected
                val recipientConnection = connectionManager.getConnection(request.recipientUserId)
                val messageId = java.util.UUID.randomUUID().toString()

                if (recipientConnection != null && connectionManager.isConnected(request.recipientUserId)) {
                    // User is online - deliver via WebSocket
                    try {
                        val chatData = app.bartering.features.chat.model.ChatMessageData(
                            id = messageId,
                            senderId = "${request.senderUserId}@${server.serverId}", // Federated user ID format
                            senderName = server.serverName ?: "Unknown Server", // Use server name as sender display name
                            recipientId = request.recipientUserId,
                            encryptedPayload = request.encryptedPayload,
                            timestamp = request.timestamp.toString()
                        )
                        
                        val relayedMessage = app.bartering.features.chat.model.ClientChatMessage(
                            data = chatData
                        )

                        recipientConnection.session.send(
                            io.ktor.websocket.Frame.Text(
                                kotlinx.serialization.json.Json.encodeToString(
                                    app.bartering.features.chat.model.SocketMessage.serializer(),
                                    relayedMessage
                                )
                            )
                        )

                        // Log successful delivery
                        federationService.logFederationEvent(
                            eventType = FederationEventType.MESSAGE_RELAY,
                            serverId = request.requestingServerId,
                            action = "message_relay_realtime",
                            outcome = FederationOutcome.SUCCESS,
                            details = mapOf(
                                "recipientUserId" to request.recipientUserId,
                                "senderUserId" to request.senderUserId,
                                "messageId" to messageId,
                                "deliveryMethod" to "websocket"
                            )
                        )

                        call.respond(HttpStatusCode.OK, FederationApiResponse<MessageRelayResponse>(
                            success = true,
                            data = MessageRelayResponse(
                                delivered = true,
                                messageId = messageId,
                                reason = null
                            ),
                            error = null,
                            timestamp = System.currentTimeMillis()
                        ))

                    } catch (e: Exception) {
                        e.printStackTrace()
                        
                        // WebSocket delivery failed, fall back to offline storage
                        storeAsOfflineMessage(
                            request = request,
                            messageId = messageId,
                            serverName = server.serverName ?: "Unknown Server",
                            offlineMessageDao = offlineMessageDao,
                            federationService = federationService
                        )

                        call.respond(HttpStatusCode.OK, FederationApiResponse<MessageRelayResponse>(
                            success = true,
                            data = MessageRelayResponse(
                                delivered = false,
                                messageId = messageId,
                                reason = "Stored for offline delivery (WebSocket error)"
                            ),
                            error = null,
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                } else {
                    // User is offline - store for later delivery
                    storeAsOfflineMessage(
                        request = request,
                        messageId = messageId,
                        serverName = server.serverName ?: "Unknown Server",
                        offlineMessageDao = offlineMessageDao,
                        federationService = federationService
                    )

                    call.respond(HttpStatusCode.OK, FederationApiResponse<MessageRelayResponse>(
                        success = true,
                        data = MessageRelayResponse(
                            delivered = false,
                            messageId = messageId,
                            reason = "User offline, stored for delivery when online"
                        ),
                        error = null,
                        timestamp = System.currentTimeMillis()
                    ))
                }

            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Failed to relay message: ${e.message}",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }

        /**
         * GET /federation/v1/postings/search
         * Receives posting search requests from federated servers.
         * Searches local postings and returns matching results.
         */
        get("/postings/search") {
            try {
                val serverId = call.request.queryParameters["serverId"] ?: ""
                val query = call.request.queryParameters["q"] ?: ""
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val isOffer = call.request.queryParameters["isOffer"]?.toBooleanStrictOrNull()
                val signature = call.request.queryParameters["signature"] ?: ""
                val timestamp = call.request.queryParameters["timestamp"]?.toLongOrNull()
                    ?: System.currentTimeMillis()

                // Validate required parameters
                if (serverId.isBlank() || query.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, FederationApiResponse(
                        success = false,
                        data = null,
                        error = "Missing required parameters: serverId and query are required",
                        timestamp = System.currentTimeMillis()
                    ))
                    return@get
                }

                // Validate federation request with all checks
                val authResult = validateFederationRequest(
                    serverId = serverId,
                    requiredScope = ScopeType.POSTINGS,
                    signatureData = "$serverId|$query|$limit|$timestamp",
                    signature = signature,
                    federationDao = federationDao,
                    federationService = federationService
                )
                
                if (call.handleAuthFailure(authResult)) return@get
                val server = (authResult as FederationAuthResult.Success).server

                // Perform search on local postings
                val searchResults = postingDao.searchPostings(
                    searchText = query,
                    latitude = null, // Don't filter by location for federated searches
                    longitude = null,
                    radiusMeters = null,
                    isOffer = isOffer,
                    limit = limit.coerceAtMost(100) // Cap at 100 to prevent abuse
                )

                // Convert to federated posting data
                val federatedPostings = searchResults.map { postingWithDistance ->
                    val posting = postingWithDistance.posting
                    FederatedPostingData(
                        postingId = posting.id,
                        userId = posting.userId,
                        title = posting.title,
                        description = posting.description,
                        value = posting.value?.toBigDecimal(),
                        imageUrls = posting.imageUrls,
                        isOffer = posting.isOffer,
                        status = posting.status.name,
                        attributes = posting.attributes.map { it.attributeId },
                        createdAt = posting.createdAt,
                        expiresAt = posting.expiresAt
                    )
                }

                // Log federation event
                federationService.logFederationEvent(
                    eventType = FederationEventType.POSTING_SEARCH,
                    serverId = serverId,
                    action = "posting_search",
                    outcome = FederationOutcome.SUCCESS,
                    details = mapOf(
                        "query" to query,
                        "isOffer" to isOffer,
                        "limit" to limit,
                        "resultsCount" to federatedPostings.size
                    )
                )

                call.respond(HttpStatusCode.OK, FederationApiResponse<PostingSearchResponse>(
                    success = true,
                    data = PostingSearchResponse(
                        postings = federatedPostings,
                        count = federatedPostings.size,
                        hasMore = searchResults.size >= limit // Indicate if there might be more results
                    ),
                    error = null,
                    timestamp = System.currentTimeMillis()
                ))

            } catch (e: Exception) {
                e.printStackTrace()
                
                // Log failure
                val serverId = call.request.queryParameters["serverId"] ?: "unknown"
                federationService.logFederationEvent(
                    eventType = FederationEventType.POSTING_SEARCH,
                    serverId = serverId,
                    action = "posting_search",
                    outcome = FederationOutcome.FAILURE,
                    details = null,
                    errorMessage = e.message
                )
                
                call.respond(HttpStatusCode.InternalServerError, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Failed to search postings: ${e.message}",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }
    }
}

/**
 * Admin routes for managing federation (called by local admins, not federated servers).
 * Now protected with admin authentication.
 */
fun Route.federationAdminRoutes() {

    val federationService by inject<FederationService>()
    val federationDao by inject<FederationDao>()
    val authenticationDao by inject<AuthenticationDaoImpl>()

    route("/api/v1/federation/admin") {

        /**
         * POST /api/v1/federation/admin/initialize
         * Initializes the local server identity.
         * Requires admin authentication.
         */
        post("/initialize") {
            // Verify admin access
            val adminUserId = call.verifyAdminAccess(authenticationDao) ?: return@post
            
            try {
                val request = call.receive<InitializeServerRequest>()

                if (request.serverUrl.isBlank() || request.serverName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, AdminErrorResponse(
                        error = "Missing required fields: serverUrl, serverName"
                    ))
                    return@post
                }

                val identity = federationService.initializeLocalServer(
                    serverUrl = request.serverUrl,
                    serverName = request.serverName,
                    adminContact = request.adminContact,
                    description = request.description,
                    locationHint = request.locationHint
                )

                call.respond(HttpStatusCode.OK, InitializeServerResponse(
                    success = true,
                    serverId = identity.serverId,
                    serverUrl = identity.serverUrl,
                    serverName = identity.serverName,
                    message = "Server identity initialized successfully"
                ))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, InitializeServerResponse(
                    success = false,
                    error = "Failed to initialize server: ${e.message}"
                ))
            }
        }

        /**
         * GET /api/v1/federation/admin/servers
         * Lists all federated servers.
         * Requires admin authentication.
         */
        get("/servers") {
            // Verify admin access
            val adminUserId = call.verifyAdminAccess(authenticationDao) ?: return@get
            
            try {
                val trustLevel = call.request.queryParameters["trustLevel"]?.let {
                    try { TrustLevel.valueOf(it.uppercase()) } catch (e: Exception) { null }
                }

                val servers = federationService.listFederatedServers(trustLevel)

                val serverInfos = servers.map { server ->
                    FederatedServerInfo(
                        serverId = server.serverId,
                        serverUrl = server.serverUrl,
                        serverName = server.serverName,
                        trustLevel = server.trustLevel.name,
                        scopePermissions = server.scopePermissions,
                        protocolVersion = server.protocolVersion,
                        isActive = server.isActive,
                        lastSyncTimestamp = server.lastSyncTimestamp,
                        federationAgreementHash = server.federationAgreementHash,
                        createdAt = server.createdAt,
                        updatedAt = server.updatedAt
                    )
                }

                call.respond(HttpStatusCode.OK, FederatedServersListResponse(
                    success = true,
                    count = serverInfos.size,
                    servers = serverInfos
                ))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, FederatedServersListResponse(
                    success = false,
                    count = 0,
                    servers = emptyList(),
                    error = "Failed to retrieve servers: ${e.message}"
                ))
            }
        }

        /**
         * POST /api/v1/federation/admin/servers/{serverId}/trust
         * Updates trust level for a server.
         * Requires admin authentication.
         */
        post("/servers/{serverId}/trust") {
            // Verify admin access
            val adminUserId = call.verifyAdminAccess(authenticationDao) ?: return@post
            
            try {
                val serverId = call.parameters["serverId"]!!
                val request = call.receive<UpdateTrustLevelRequest>()

                if (request.trustLevel.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, UpdateTrustLevelResponse(
                        success = false,
                        error = "Missing required field: trustLevel"
                    ))
                    return@post
                }

                val trustLevel = try {
                    TrustLevel.valueOf(request.trustLevel.uppercase())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, UpdateTrustLevelResponse(
                        success = false,
                        error = "Invalid trust level. Must be one of: FULL, PARTIAL, PENDING, BLOCKED"
                    ))
                    return@post
                }

                val success = federationService.updateTrustLevel(serverId, trustLevel)

                if (success) {
                    call.respond(HttpStatusCode.OK, UpdateTrustLevelResponse(
                        success = true,
                        serverId = serverId,
                        trustLevel = trustLevel.name,
                        message = "Trust level updated successfully"
                    ))
                } else {
                    call.respond(HttpStatusCode.NotFound, UpdateTrustLevelResponse(
                        success = false,
                        error = "Server not found"
                    ))
                }

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, UpdateTrustLevelResponse(
                    success = false,
                    error = "Failed to update trust level: ${e.message}"
                ))
            }
        }

        /**
         * POST /api/v1/federation/admin/handshake
         * Initiates handshake with another server.
         * Requires admin authentication.
         */
        post("/handshake") {
            // Verify admin access
            val adminUserId = call.verifyAdminAccess(authenticationDao) ?: return@post
            
            try {
                val request = call.receive<InitiateHandshakeRequest>()

                if (request.targetServerUrl.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, InitiateHandshakeResponse(
                        success = false,
                        error = "Missing required field: targetServerUrl"
                    ))
                    return@post
                }

                val response = federationService.initiateHandshake(
                    targetServerUrl = request.targetServerUrl,
                    proposedScopes = request.proposedScopes
                )

                call.respond(HttpStatusCode.OK, InitiateHandshakeResponse(
                    success = true,
                    response = response,
                    message = if (response.accepted) "Handshake accepted" else "Handshake rejected: ${response.reason}"
                ))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, InitiateHandshakeResponse(
                    success = false,
                    error = "Failed to initiate handshake: ${e.message}"
                ))
            }
        }

        /**
         * GET /api/v1/federation/admin/proxy/users/nearby
         * Admin endpoint to test cross-server user search with proper signature.
         * Generates signature and proxies request to target federated server.
         */
        get("/proxy/users/nearby") {
            try {
                val targetServerId = call.request.queryParameters["targetServerId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "error" to "Missing targetServerId parameter"
                    ))
                    return@get
                }
                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull() ?: 56.95
                val lon = call.request.queryParameters["lon"]?.toDoubleOrNull() ?: 24.10
                val radius = call.request.queryParameters["radius"]?.toDoubleOrNull() ?: 50000.0
                
                // Get target server info
                val targetServer = federationDao.getFederatedServer(targetServerId) ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf(
                        "success" to false,
                        "error" to "Target server not found"
                    ))
                    return@get
                }
                
                // Get our local identity
                val localIdentity = federationDao.getLocalServerIdentity() ?: run {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "success" to false,
                        "error" to "Local server not initialized"
                    ))
                    return@get
                }
                
                // Generate timestamp and signature
                val timestamp = System.currentTimeMillis()
                val signatureData = "${localIdentity.serverId}|$lat|$lon|$radius|$timestamp"
                val signature = federationService.signWithLocalKey(signatureData)
                
                // Build target URL
                val targetUrl = "${targetServer.serverUrl}/federation/v1/users/nearby" +
                    "?serverId=${localIdentity.serverId}" +
                    "&lat=$lat" +
                    "&lon=$lon" +
                    "&radius=$radius" +
                    "&timestamp=$timestamp" +
                    "&signature=$signature"
                
                call.respond(HttpStatusCode.OK, mapOf(
                    "success" to true,
                    "targetUrl" to targetUrl,
                    "signatureData" to signatureData,
                    "signature" to signature,
                    "localServerId" to localIdentity.serverId,
                    "targetServerId" to targetServerId
                ))
                
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "success" to false,
                    "error" to "Failed to generate proxy URL: ${e.message}"
                ))
            }
        }

        /**
         * GET /api/v1/federation/admin/proxy/postings/search
         * Admin endpoint to test cross-server posting search with proper signature.
         */
        get("/proxy/postings/search") {
            try {
                val targetServerId = call.request.queryParameters["targetServerId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "error" to "Missing targetServerId parameter"
                    ))
                    return@get
                }
                val query = call.request.queryParameters["q"] ?: "test"
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                
                // Get target server info
                val targetServer = federationDao.getFederatedServer(targetServerId) ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf(
                        "success" to false,
                        "error" to "Target server not found"
                    ))
                    return@get
                }
                
                // Get our local identity
                val localIdentity = federationDao.getLocalServerIdentity() ?: run {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "success" to false,
                        "error" to "Local server not initialized"
                    ))
                    return@get
                }
                
                // Generate timestamp and signature
                val timestamp = System.currentTimeMillis()
                val signatureData = "${localIdentity.serverId}|$query|$limit|$timestamp"
                val signature = federationService.signWithLocalKey(signatureData)
                
                // Build target URL
                val targetUrl = "${targetServer.serverUrl}/federation/v1/postings/search" +
                    "?serverId=${localIdentity.serverId}" +
                    "&q=$query" +
                    "&limit=$limit" +
                    "&timestamp=$timestamp" +
                    "&signature=$signature"
                
                call.respond(HttpStatusCode.OK, mapOf(
                    "success" to true,
                    "targetUrl" to targetUrl,
                    "signatureData" to signatureData,
                    "signature" to signature,
                    "localServerId" to localIdentity.serverId,
                    "targetServerId" to targetServerId
                ))
                
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "success" to false,
                    "error" to "Failed to generate proxy URL: ${e.message}"
                ))
            }
        }
    }
}

/**
 * Result of federation authorization check.
 * Contains either the validated server or error response details.
 */
private sealed class FederationAuthResult {
    data class Success(val server: FederatedServer) : FederationAuthResult()
    data class Failure(
        val statusCode: HttpStatusCode,
        val error: String
    ) : FederationAuthResult()
}

/**
 * Retrieves and validates a federated server with comprehensive checks.
 * 
 * Performs the following validations:
 * 1. Retrieves server from DAO (handles database errors)
 * 2. Checks if server exists
 * 3. Verifies server is active
 * 4. Verifies server is not blocked
 * 5. Verifies required scope permission
 * 6. Verifies request signature
 * 
 * @param serverId The ID of the requesting server
 * @param requiredScope The scope permission required for this endpoint (null to skip scope check)
 * @param signatureData The data string used to generate the signature
 * @param signature The signature to verify
 * @param federationDao DAO for federation operations
 * @param federationService Service for signature verification
 * @return FederationAuthResult.Success with server or FederationAuthResult.Failure with error details
 */
private suspend fun validateFederationRequest(
    serverId: String,
    requiredScope: ScopeType? = null,
    signatureData: String,
    signature: String,
    federationDao: FederationDao,
    federationService: FederationService
): FederationAuthResult {
    
    // 1. Retrieve server from DAO
    val server = try {
        federationDao.getFederatedServer(serverId)
    } catch (e: Exception) {
        return FederationAuthResult.Failure(
            statusCode = HttpStatusCode.InternalServerError,
            error = "Failed to retrieve server information: ${e.message}"
        )
    }
    
    // 2. Check if server exists
    if (server == null) {
        return FederationAuthResult.Failure(
            statusCode = HttpStatusCode.NotFound,
            error = "Server not found. Please initiate handshake first."
        )
    }
    
    // 3. Verify server is active
    if (!server.isActive) {
        return FederationAuthResult.Failure(
            statusCode = HttpStatusCode.Forbidden,
            error = "Server is not active"
        )
    }
    
    // 4. Verify server is not blocked
    if (server.trustLevel == TrustLevel.BLOCKED) {
        return FederationAuthResult.Failure(
            statusCode = HttpStatusCode.Forbidden,
            error = "Server is blocked"
        )
    }
    
    // 5. Verify required scope permission (if specified)
    if (requiredScope != null) {
        val hasScope = when (requiredScope) {
            ScopeType.USERS -> server.scopePermissions.users
            ScopeType.POSTINGS -> server.scopePermissions.postings
            ScopeType.CHAT -> server.scopePermissions.chat
            ScopeType.GEOLOCATION -> server.scopePermissions.geolocation
            ScopeType.ATTRIBUTES -> server.scopePermissions.attributes
        }
        
        if (!hasScope) {
            val currentScopes = buildString {
                append("users=${server.scopePermissions.users}, ")
                append("postings=${server.scopePermissions.postings}, ")
                append("chat=${server.scopePermissions.chat}, ")
                append("geolocation=${server.scopePermissions.geolocation}, ")
                append("attributes=${server.scopePermissions.attributes}")
            }
            
            return FederationAuthResult.Failure(
                statusCode = HttpStatusCode.Forbidden,
                error = "${requiredScope.name.lowercase().replaceFirstChar { it.uppercase() }} scope not authorized for this server. Current scopes: $currentScopes"
            )
        }
    }
    
    // 6. Verify signature
    val signatureValid = federationService.verifyServerSignature(
        serverId = serverId,
        data = signatureData,
        signature = signature
    )
    
    if (!signatureValid) {
        return FederationAuthResult.Failure(
            statusCode = HttpStatusCode.Unauthorized,
            error = "Invalid signature"
        )
    }
    
    return FederationAuthResult.Success(server)
}

/**
 * Enum representing the different scope types.
 */
private enum class ScopeType {
    USERS,
    POSTINGS,
    CHAT,
    GEOLOCATION,
    ATTRIBUTES
}

/**
 * Extension function to handle FederationAuthResult and respond with error if needed.
 * Returns true if validation failed (and response was sent), false if validation succeeded.
 *
 */
private suspend fun ApplicationCall.handleAuthFailure(result: FederationAuthResult): Boolean {
    return when (result) {
        is FederationAuthResult.Failure -> {
            respond(result.statusCode, FederationApiResponse<Nothing>(
                success = false,
                data = null,
                error = result.error,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
        is FederationAuthResult.Success -> false
    }
}

/**
 * Helper function to store a federated message for offline delivery.
 */
private suspend fun storeAsOfflineMessage(
    request: MessageRelayRequest,
    messageId: String,
    serverName: String,
    offlineMessageDao: app.bartering.features.chat.dao.OfflineMessageDao,
    federationService: FederationService
) {
    try {
        val offlineMessage = app.bartering.features.chat.model.OfflineMessageDto(
            id = messageId,
            senderId = "${request.senderUserId}@${request.requestingServerId}", // Federated user ID
            recipientId = request.recipientUserId,
            senderName = serverName, // Display server name as sender
            encryptedPayload = request.encryptedPayload,
            timestamp = request.timestamp,
            delivered = false
        )

        offlineMessageDao.storeOfflineMessage(offlineMessage)

        // Log offline storage
        federationService.logFederationEvent(
            eventType = FederationEventType.MESSAGE_RELAY,
            serverId = request.requestingServerId,
            action = "message_relay_offline",
            outcome = FederationOutcome.SUCCESS,
            details = mapOf(
                "recipientUserId" to request.recipientUserId,
                "senderUserId" to request.senderUserId,
                "messageId" to messageId,
                "deliveryMethod" to "offline_storage"
            )
        )
    } catch (e: Exception) {
        e.printStackTrace()

        federationService.logFederationEvent(
            eventType = FederationEventType.MESSAGE_RELAY,
            serverId = request.requestingServerId,
            action = "message_relay_offline",
            outcome = FederationOutcome.FAILURE,
            details = null,
            errorMessage = "Failed to store offline message: ${e.message}"
        )
    }

}