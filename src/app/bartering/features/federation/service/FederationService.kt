package app.bartering.features.federation.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.bartering.extensions.DatabaseFactory
import app.bartering.features.federation.crypto.FederationCrypto
import app.bartering.features.federation.dao.FederationDao
import app.bartering.features.federation.model.*
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Service layer for federation operations.
 * Handles server identity, handshakes, trust management, and cryptographic operations.
 */
interface FederationService {

    suspend fun initializeLocalServer(
        serverUrl: String,
        serverName: String,
        adminContact: String?,
        description: String?,
        locationHint: String?
    ): LocalServerIdentity

    suspend fun getLocalServerIdentity(): PublicServerIdentity?

    suspend fun initiateHandshake(
        targetServerUrl: String,
        proposedScopes: FederationScope
    ): FederationHandshakeResponse

    suspend fun acceptHandshake(
        request: FederationHandshakeRequest,
        acceptedScopes: FederationScope
    ): FederationHandshakeResponse

    suspend fun listFederatedServers(trustLevel: TrustLevel? = null): List<FederatedServer>

    suspend fun updateTrustLevel(serverId: String, trustLevel: TrustLevel): Boolean

    suspend fun updateScopes(serverId: String, scopes: FederationScope): Boolean

    suspend fun verifyServerSignature(
        serverId: String,
        data: String,
        signature: String
    ): Boolean

    suspend fun signWithLocalKey(data: String): String

    suspend fun logFederationEvent(
        eventType: FederationEventType,
        serverId: String?,
        action: String,
        outcome: FederationOutcome,
        details: Map<String, Any?>?,
        errorMessage: String? = null,
        durationMs: Long? = null
    )
}

class FederationServiceImpl(
    private val dao: FederationDao
) : FederationService {

    private val httpClient = HttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun initializeLocalServer(
        serverUrl: String,
        serverName: String,
        adminContact: String?,
        description: String?,
        locationHint: String?
    ): LocalServerIdentity = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Check if already initialized
            val existing = dao.getLocalServerIdentity()
            if (existing != null) {
                logFederationEvent(
                    eventType = FederationEventType.KEY_ROTATION,
                    serverId = null,
                    action = "SERVER_ALREADY_INITIALIZED",
                    outcome = FederationOutcome.SUCCESS,
                    details = mapOf("serverId" to existing.serverId),
                    durationMs = null
                )
                return@withContext existing
            }

            // Generate cryptographic keys
            val keyPair = FederationCrypto.generateKeyPair(2048)
            val serverId = FederationCrypto.generateServerId()
            val publicKeyPem = FederationCrypto.publicKeyToPem(keyPair.public)
            val privateKeyPem = FederationCrypto.privateKeyToPem(keyPair.private)

            val now = Instant.now()
            val keyRotationDue = now.plus(30, ChronoUnit.DAYS)

            val identity = LocalServerIdentity(
                serverId = serverId,
                serverUrl = serverUrl,
                serverName = serverName,
                publicKey = publicKeyPem,
                privateKey = privateKeyPem,
                keyAlgorithm = "RSA",
                keySize = 2048,
                protocolVersion = "1.0",
                adminContact = adminContact,
                description = description,
                locationHint = locationHint,
                keyGeneratedAt = now,
                keyRotationDue = keyRotationDue,
                createdAt = now,
                updatedAt = now
            )

            dao.saveLocalServerIdentity(identity)

            logFederationEvent(
                eventType = FederationEventType.KEY_ROTATION,
                serverId = null,
                action = "INITIALIZE_SERVER",
                outcome = FederationOutcome.SUCCESS,
                details = mapOf(
                    "serverId" to serverId,
                    "serverName" to serverName,
                    "serverUrl" to serverUrl
                ),
                durationMs = System.currentTimeMillis() - startTime
            )

            identity

        } catch (e: Exception) {
            logFederationEvent(
                eventType = FederationEventType.ERROR,
                serverId = null,
                action = "INITIALIZE_SERVER",
                outcome = FederationOutcome.FAILURE,
                details = null,
                errorMessage = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
            throw e
        }
    }

    override suspend fun getLocalServerIdentity(): PublicServerIdentity? = withContext(Dispatchers.IO) {
        dao.getLocalServerIdentity()?.toPublicIdentity()
    }

    override suspend fun initiateHandshake(
        targetServerUrl: String,
        proposedScopes: FederationScope
    ): FederationHandshakeResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Get local server identity
            val localIdentity = dao.getLocalServerIdentity()
                ?: throw IllegalStateException("Local server not initialized. Call initializeLocalServer() first.")

            val privateKey = FederationCrypto.pemToPrivateKey(localIdentity.privateKey)
            val timestamp = System.currentTimeMillis()

            // Create handshake request
            val request = FederationHandshakeRequest(
                serverId = localIdentity.serverId,
                serverUrl = localIdentity.serverUrl,
                serverName = localIdentity.serverName,
                publicKey = localIdentity.publicKey,
                protocolVersion = localIdentity.protocolVersion,
                proposedScopes = proposedScopes,
                timestamp = timestamp,
                signature = ""
            )

            // Sign the request
            val requestJson = json.encodeToString(request.copy(signature = ""))
            val signature = FederationCrypto.signFederationMessage(
                serverId = localIdentity.serverId,
                timestamp = timestamp,
                payload = requestJson,
                privateKey = privateKey
            )
            val signedRequest = request.copy(signature = signature)

            // Send to target server
            val targetUrl = "${targetServerUrl.trimEnd('/')}/federation/v1/handshake"

            val response: FederationApiResponse<FederationHandshakeResponse> =
                httpClient.post(targetUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(signedRequest)
                }.body()

            if (response.data == null) {
                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE_REJECT,
                    serverId = targetUrl,
                    action = "INITIATE_HANDSHAKE",
                    outcome = FederationOutcome.REJECTED,
                    details = mapOf("targetServer" to targetUrl),
                    errorMessage = response.error,
                    durationMs = System.currentTimeMillis() - startTime
                )

                return@withContext FederationHandshakeResponse(
                    accepted = false,
                    serverId = "",
                    serverUrl = "",
                    serverName = "",
                    publicKey = "",
                    protocolVersion = "",
                    acceptedScopes = FederationScope.NONE,
                    agreementHash = "",
                    timestamp = System.currentTimeMillis(),
                    signature = "",
                    reason = response.error ?: "Unknown error"
                )
            }

            val handshakeResponse = response.data!!

            // Verify the response signature
            val remotePublicKey = FederationCrypto.pemToPublicKey(handshakeResponse.publicKey)
            val responseJson = json.encodeToString(handshakeResponse.copy(signature = ""))
            val signatureValid = FederationCrypto.verifyFederationMessage(
                serverId = handshakeResponse.serverId,
                timestamp = handshakeResponse.timestamp,
                payload = responseJson,
                signatureBase64 = handshakeResponse.signature,
                publicKey = remotePublicKey
            )

            if (!signatureValid) {
                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE_REJECT,
                    serverId = handshakeResponse.serverId,
                    action = "INITIATE_HANDSHAKE",
                    outcome = FederationOutcome.FAILURE,
                    details = mapOf("targetServer" to targetUrl),
                    errorMessage = "Invalid signature in handshake response",
                    durationMs = System.currentTimeMillis() - startTime
                )

                throw SecurityException("Invalid signature in handshake response")
            }

            // Save the federated server if accepted
            if (handshakeResponse.accepted) {
                val federatedServer = FederatedServer(
                    serverId = handshakeResponse.serverId,
                    serverUrl = handshakeResponse.serverUrl,
                    serverName = handshakeResponse.serverName,
                    publicKey = handshakeResponse.publicKey,
                    trustLevel = TrustLevel.PENDING,
                    scopePermissions = handshakeResponse.acceptedScopes,
                    federationAgreementHash = handshakeResponse.agreementHash,
                    lastSyncTimestamp = null,
                    serverMetadata = mapOf(
                        "protocolVersion" to handshakeResponse.protocolVersion,
                        "handshakeTimestamp" to handshakeResponse.timestamp.toString()
                    ),
                    protocolVersion = handshakeResponse.protocolVersion,
                    isActive = true,
                    dataRetentionDays = 30,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )

                dao.createFederatedServer(federatedServer)

                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE_ACCEPT,
                    serverId = handshakeResponse.serverId,
                    action = "INITIATE_HANDSHAKE",
                    outcome = FederationOutcome.SUCCESS,
                    details = mapOf(
                        "targetServer" to targetUrl,
                        "agreementHash" to handshakeResponse.agreementHash,
                        "acceptedScopes" to handshakeResponse.acceptedScopes.toString()
                    ),
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE_REJECT,
                    serverId = targetUrl,
                    action = "INITIATE_HANDSHAKE",
                    outcome = FederationOutcome.REJECTED,
                    details = mapOf("targetServer" to targetUrl),
                    errorMessage = handshakeResponse.reason,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            handshakeResponse

        } catch (e: Exception) {
            logFederationEvent(
                eventType = FederationEventType.ERROR,
                serverId = targetServerUrl,
                action = "INITIATE_HANDSHAKE",
                outcome = FederationOutcome.FAILURE,
                details = null,
                errorMessage = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
            throw e
        }
    }

    override suspend fun acceptHandshake(
        request: FederationHandshakeRequest,
        acceptedScopes: FederationScope
    ): FederationHandshakeResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Get local server identity
            val localIdentity = dao.getLocalServerIdentity()
                ?: throw IllegalStateException("Local server not initialized. Call initializeLocalServer() first.")

            // Validate timestamp (reject if more than 5 minutes old)
            val now = System.currentTimeMillis()
            val maxAge = 5.minutes.inWholeMilliseconds
            if (now - request.timestamp > maxAge) {
                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE_REJECT,
                    serverId = request.serverId,
                    action = "ACCEPT_HANDSHAKE",
                    outcome = FederationOutcome.FAILURE,
                    details = mapOf(
                        "requestTimestamp" to request.timestamp,
                        "currentTimestamp" to now,
                        "maxAge" to maxAge
                    ),
                    errorMessage = "Handshake request timestamp too old",
                    durationMs = null
                )

                return@withContext FederationHandshakeResponse(
                    accepted = false,
                    serverId = localIdentity.serverId,
                    serverUrl = localIdentity.serverUrl,
                    serverName = localIdentity.serverName,
                    publicKey = localIdentity.publicKey,
                    protocolVersion = localIdentity.protocolVersion,
                    acceptedScopes = FederationScope.NONE,
                    agreementHash = "",
                    timestamp = now,
                    signature = "",
                    reason = "Request timestamp too old"
                )
            }

            // Verify incoming signature
            val remotePublicKey = FederationCrypto.pemToPublicKey(request.publicKey)
            val requestJson = json.encodeToString(request.copy(signature = ""))
            val signatureValid = FederationCrypto.verifyFederationMessage(
                serverId = request.serverId,
                timestamp = request.timestamp,
                payload = requestJson,
                signatureBase64 = request.signature,
                publicKey = remotePublicKey
            )

            if (!signatureValid) {
                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE_REJECT,
                    serverId = request.serverId,
                    action = "ACCEPT_HANDSHAKE",
                    outcome = FederationOutcome.FAILURE,
                    details = mapOf("serverUrl" to request.serverUrl),
                    errorMessage = "Invalid signature in handshake request",
                    durationMs = null
                )

                return@withContext FederationHandshakeResponse(
                    accepted = false,
                    serverId = localIdentity.serverId,
                    serverUrl = localIdentity.serverUrl,
                    serverName = localIdentity.serverName,
                    publicKey = localIdentity.publicKey,
                    protocolVersion = localIdentity.protocolVersion,
                    acceptedScopes = FederationScope.NONE,
                    agreementHash = "",
                    timestamp = now,
                    signature = "",
                    reason = "Invalid signature"
                )
            }

            // Check if server already exists
            val existingServer = dao.getFederatedServer(request.serverId)

            // Generate agreement hash
            val agreementHash = FederationCrypto.generateAgreementHash(
                localIdentity.serverId,
                request.serverId,
                acceptedScopes.toString(),
                now
            )

            // Create response
            val response = FederationHandshakeResponse(
                accepted = true,
                serverId = localIdentity.serverId,
                serverUrl = localIdentity.serverUrl,
                serverName = localIdentity.serverName,
                publicKey = localIdentity.publicKey,
                protocolVersion = localIdentity.protocolVersion,
                acceptedScopes = acceptedScopes,
                agreementHash = agreementHash,
                timestamp = now,
                signature = "",
                reason = null
            )

            // Sign the response
            val privateKey = FederationCrypto.pemToPrivateKey(localIdentity.privateKey)
            val responseJson = json.encodeToString(response.copy(signature = ""))
            val signature = FederationCrypto.signFederationMessage(
                serverId = localIdentity.serverId,
                timestamp = now,
                payload = responseJson,
                privateKey = privateKey
            )
            val signedResponse = response.copy(signature = signature)

            // Save or update federated server
            val federatedServer = FederatedServer(
                serverId = request.serverId,
                serverUrl = request.serverUrl,
                serverName = request.serverName,
                publicKey = request.publicKey,
                trustLevel = TrustLevel.PENDING, // Will be updated to FULL/PARTIAL by admin
                scopePermissions = acceptedScopes,
                federationAgreementHash = agreementHash,
                lastSyncTimestamp = Instant.now(),
                serverMetadata = mapOf(
                    "protocolVersion" to request.protocolVersion,
                    "handshakeTimestamp" to request.timestamp.toString(),
                    "proposedScopes" to request.proposedScopes.toString()
                ),
                protocolVersion = request.protocolVersion,
                isActive = true,
                dataRetentionDays = 30,
                createdAt = existingServer?.createdAt ?: Instant.now(),
                updatedAt = Instant.now()
            )

            if (existingServer != null) {
                dao.updateFederatedServer(request.serverId, mapOf(
                    "serverName" to request.serverName,
                    "trustLevel" to TrustLevel.PENDING,
                    "scopePermissions" to acceptedScopes,
                    "federationAgreementHash" to agreementHash,
                    "serverMetadata" to federatedServer.serverMetadata,
                    "isActive" to true
                ))
            } else {
                dao.createFederatedServer(federatedServer)
            }

            logFederationEvent(
                eventType = FederationEventType.HANDSHAKE_ACCEPT,
                serverId = request.serverId,
                action = "ACCEPT_HANDSHAKE",
                outcome = FederationOutcome.SUCCESS,
                details = mapOf(
                    "serverUrl" to request.serverUrl,
                    "agreementHash" to agreementHash,
                    "acceptedScopes" to acceptedScopes.toString(),
                    "isNewServer" to (existingServer == null)
                ),
                durationMs = System.currentTimeMillis() - startTime
            )

            signedResponse

        } catch (e: Exception) {
            logFederationEvent(
                eventType = FederationEventType.ERROR,
                serverId = request.serverId,
                action = "ACCEPT_HANDSHAKE",
                outcome = FederationOutcome.FAILURE,
                details = null,
                errorMessage = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
            throw e
        }
    }

    override suspend fun listFederatedServers(trustLevel: TrustLevel?): List<FederatedServer> {
        return dao.listFederatedServers(trustLevel)
    }

    override suspend fun updateTrustLevel(serverId: String, trustLevel: TrustLevel): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val result = dao.updateServerTrustLevel(serverId, trustLevel)

            logFederationEvent(
                eventType = FederationEventType.TRUST_LEVEL_CHANGE,
                serverId = serverId,
                action = "UPDATE_TRUST_LEVEL",
                outcome = if (result) FederationOutcome.SUCCESS else FederationOutcome.FAILURE,
                details = mapOf("newTrustLevel" to trustLevel.name),
                durationMs = System.currentTimeMillis() - startTime
            )

            result
        } catch (e: Exception) {
            logFederationEvent(
                eventType = FederationEventType.ERROR,
                serverId = serverId,
                action = "UPDATE_TRUST_LEVEL",
                outcome = FederationOutcome.FAILURE,
                details = mapOf("newTrustLevel" to trustLevel.name),
                errorMessage = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
            false
        }
    }

    override suspend fun updateScopes(serverId: String, scopes: FederationScope): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val result = dao.updateServerScopes(serverId, scopes)

            logFederationEvent(
                eventType = FederationEventType.SCOPE_UPDATE,
                serverId = serverId,
                action = "UPDATE_SCOPES",
                outcome = if (result) FederationOutcome.SUCCESS else FederationOutcome.FAILURE,
                details = mapOf("newScopes" to scopes.toString()),
                durationMs = System.currentTimeMillis() - startTime
            )

            result
        } catch (e: Exception) {
            logFederationEvent(
                eventType = FederationEventType.ERROR,
                serverId = serverId,
                action = "UPDATE_SCOPES",
                outcome = FederationOutcome.FAILURE,
                details = mapOf("newScopes" to scopes.toString()),
                errorMessage = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
            false
        }
    }

    override suspend fun verifyServerSignature(
        serverId: String,
        data: String,
        signature: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val server = dao.getFederatedServer(serverId)
                ?: return@withContext false

            val publicKey = FederationCrypto.pemToPublicKey(server.publicKey)
            FederationCrypto.verify(data, signature, publicKey)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun signWithLocalKey(data: String): String = withContext(Dispatchers.IO) {
        val localIdentity = dao.getLocalServerIdentity()
            ?: throw IllegalStateException("Local server not initialized")

        val privateKey = FederationCrypto.pemToPrivateKey(localIdentity.privateKey)
        FederationCrypto.sign(data, privateKey)
    }

    override suspend fun logFederationEvent(
        eventType: FederationEventType,
        serverId: String?,
        action: String,
        outcome: FederationOutcome,
        details: Map<String, Any?>?,
        errorMessage: String?,
        durationMs: Long?
    ) {
        // Convert nullable values to empty strings for database compatibility
        val detailsMap: Map<String, Any>? = details?.mapValues {
            it.value ?: ""
        }

        dao.logFederationEvent(
            eventType = eventType,
            serverId = serverId,
            action = action,
            outcome = outcome,
            details = detailsMap,
            errorMessage = errorMessage,
            durationMs = durationMs
        )
    }
}