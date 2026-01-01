package org.barter.features.federation.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.barter.features.federation.model.*
import org.barter.features.federation.service.FederationService
import org.koin.ktor.ext.inject

/**
 * Federation API routes for server-to-server communication.
 * These endpoints are called by other federated servers, not by client apps.
 */
fun Route.federationRoutes() {

    val federationService by inject<FederationService>()

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

                call.respond(HttpStatusCode.OK, FederationApiResponse(
                    success = true,
                    data = response,
                    error = null,
                    timestamp = System.currentTimeMillis()
                ))

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
         * Receives requests to sync user data.
         * TODO: Implement user sync when federated user tables are ready
         */
        post("/sync-users") {
            val request = call.receive<UserSyncRequest>()

            // Verify signature
            val signatureValid = federationService.verifyServerSignature(
                serverId = request.requestingServerId,
                data = "${request.requestingServerId}|${request.timestamp}",
                signature = request.signature
            )

            if (!signatureValid) {
                call.respond(HttpStatusCode.Unauthorized, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Invalid signature",
                    timestamp = System.currentTimeMillis()
                ))
                return@post
            }

            call.respond(HttpStatusCode.NotImplemented, FederationApiResponse(
                success = false,
                data = null,
                error = "User sync not yet implemented",
                timestamp = System.currentTimeMillis()
            ))
        }

        /**
         * GET /federation/v1/users/nearby
         * Receives geolocation-based user search requests from federated servers.
         * TODO: Implement when geolocation scope is approved
         */
        get("/users/nearby") {
            val serverId = call.request.queryParameters["serverId"] ?: ""
            val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
            val radius = call.request.queryParameters["radius"]?.toDoubleOrNull() ?: 50.0
            val signature = call.request.queryParameters["signature"] ?: ""
            val timestamp = call.request.queryParameters["timestamp"]?.toLongOrNull()
                ?: System.currentTimeMillis()

            // Require geolocation scope for this endpoint
            // TODO: Fetch server from DAO and verify geolocation scope
            if (serverId.isBlank()) {
                call.respond(HttpStatusCode.Forbidden, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Server ID required for geolocation access",
                    timestamp = System.currentTimeMillis()
                ))
                return@get
            }

            // For now, block all geolocation requests until scope verification is implemented
            call.respond(HttpStatusCode.Forbidden, FederationApiResponse(
                success = false,
                data = null,
                error = "Geolocation scope not authorized for this server",
                timestamp = System.currentTimeMillis()
            ))
            return@get

            call.respond(HttpStatusCode.NotImplemented, FederationApiResponse(
                success = false,
                data = null,
                error = "Federated user search not yet implemented",
                timestamp = System.currentTimeMillis()
            ))
        }

        /**
         * POST /federation/v1/messages/relay
         * Receives message relay requests for cross-server chat.
         * TODO: Implement when chat scope is approved
         */
        post("/messages/relay") {
            val request = call.receive<MessageRelayRequest>()

            // Verify signature
            val signatureValid = federationService.verifyServerSignature(
                serverId = request.requestingServerId,
                data = "${request.requestingServerId}|${request.timestamp}|${request.encryptedPayload}",
                signature = request.signature
            )

            if (!signatureValid) {
                call.respond(HttpStatusCode.Unauthorized, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Invalid signature",
                    timestamp = System.currentTimeMillis()
                ))
                return@post
            }

            call.respond(HttpStatusCode.NotImplemented, FederationApiResponse(
                success = false,
                data = null,
                error = "Message relay not yet implemented",
                timestamp = System.currentTimeMillis()
            ))
        }

        /**
         * GET /federation/v1/postings/search
         * Receives posting search requests from federated servers.
         * TODO: Implement when postings scope is approved
         */
        get("/postings/search") {
            val serverId = call.request.queryParameters["serverId"] ?: ""
            val query = call.request.queryParameters["q"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val signature = call.request.queryParameters["signature"] ?: ""
            val timestamp = call.request.queryParameters["timestamp"]?.toLongOrNull()
                ?: System.currentTimeMillis()

            // Require postings scope for this endpoint
            // TODO: Fetch from DAO and check scopes

            if (serverId.isBlank() || query.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, FederationApiResponse(
                    success = false,
                    data = null,
                    error = "Missing required parameters",
                    timestamp = System.currentTimeMillis()
                ))
                return@get
            }

            call.respond(HttpStatusCode.NotImplemented, FederationApiResponse(
                success = false,
                data = null,
                error = "Federated posting search not yet implemented",
                timestamp = System.currentTimeMillis()
            ))
        }
    }
}

/**
 * Admin routes for managing federation (called by local admins, not federated servers).
 * TODO: Add authentication
 */
fun Route.federationAdminRoutes() {

    val federationService by inject<FederationService>()

    route("/api/v1/federation/admin") {

        /**
         * POST /api/v1/federation/admin/initialize
         * Initializes the local server identity.
         */
        post("/initialize") {
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
         */
        get("/servers") {
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
         */
        post("/servers/{serverId}/trust") {
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
         */
        post("/handshake") {
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
    }
}