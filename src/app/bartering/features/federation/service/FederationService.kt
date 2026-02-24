package app.bartering.features.federation.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
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

            // Generate new RSA key pair
            val keyPair = FederationCrypto.generateKeyPair()
            val serverId = java.util.UUID.randomUUID().toString()
            val now = Instant.now()
            val keyRotationDue = now.plus(365, ChronoUnit.DAYS)

            val identity = LocalServerIdentity(
                serverId = serverId,
                serverUrl = serverUrl,
                serverName = serverName,
                publicKey = FederationCrypto.publicKeyToPem(keyPair.public),
                privateKey = FederationCrypto.privateKeyToPem(keyPair.private),
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

            // Save to database
            dao.saveLocalServerIdentity(identity)

            val duration = System.currentTimeMillis() - startTime
            logFederationEvent(
                eventType = FederationEventType.KEY_ROTATION,
                serverId = serverId,
                action = "INITIALIZE_SERVER",
                outcome = FederationOutcome.SUCCESS,
                details = mapOf(
                    "serverUrl" to serverUrl,
                    "serverName" to serverName,
                    "keySize" to 2048
                ),
                durationMs = duration
            )

            identity
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logFederationEvent(
                eventType = FederationEventType.ERROR,
                serverId = null,
                action = "INITIALIZE_SERVER",
                outcome = FederationOutcome.FAILURE,
                details = null,
                errorMessage = e.message,
                durationMs = duration
            )
            throw e
        }
    }

    override suspend fun getLocalServerIdentity(): PublicServerIdentity? {
        return dao.getLocalServerIdentity()?.toPublicIdentity()
    }

    override suspend fun initiateHandshake(
        targetServerUrl: String,
        proposedScopes: FederationScope
    ): FederationHandshakeResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Get our local identity
            val localIdentity = dao.getLocalServerIdentity()
                ?: throw IllegalStateException("Local server identity not initialized")

            // Create and sign the handshake request
            val timestamp = System.currentTimeMillis()
            val request = FederationHandshakeRequest(
                serverId = localIdentity.serverId,
                serverUrl = localIdentity.serverUrl,
                serverName = localIdentity.serverName,
                publicKey = localIdentity.publicKey,
                protocolVersion = localIdentity.protocolVersion,
                proposedScopes = proposedScopes,
                timestamp = timestamp,
                signature = "" // Will be computed below
            )

            // Sign the request
            val dataToSign = "${request.serverId}|${request.serverUrl}|${request.timestamp}"
            val signedRequest = request.copy(
                signature = signWithLocalKey(dataToSign)
            )

            // Send request to target server
            val response: FederationHandshakeResponse = httpClient.post("$targetServerUrl/federation/v1/handshake") {
                contentType(ContentType.Application.Json)
                setBody(signedRequest)
            }.body()

            val duration = System.currentTimeMillis() - startTime

            if (response.accepted) {
                // Store the federated server info
                val server = FederatedServer(
                    serverId = response.serverId,
                    serverUrl = response.serverUrl,
                    serverName = response.serverName,
                    publicKey = response.publicKey,
                    trustLevel = TrustLevel.PENDING,
                    scopePermissions = response.acceptedScopes,
                    protocolVersion = response.protocolVersion,
                    isActive = true,
                    federationAgreementHash = response.agreementHash,
                    lastSyncTimestamp = null,
                    serverMetadata = null,
                    dataRetentionDays = 30,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
                dao.createFederatedServer(server)

                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE,
                    serverId = response.serverId,
                    action = "HANDSHAKE_INITIATE",
                    outcome = FederationOutcome.SUCCESS,
                    details = mapOf(
                        "targetServerUrl" to targetServerUrl,
                        "acceptedScopes" to response.acceptedScopes.toString()
                    ),
                    durationMs = duration
                )
            } else {
                logFederationEvent(
                    eventType = FederationEventType.HANDSHAKE,
                    serverId = null,
                    action = "HANDSHAKE_INITIATE",
                    outcome = FederationOutcome.REJECTED,
                    details = mapOf(
                        "targetServerUrl" to targetServerUrl,
                        "reason" to (response.reason ?: "Unknown")
                    ),
                    durationMs = duration
                )
            }

            response
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logFederationEvent(
                eventType = FederationEventType.HANDSHAKE,
                serverId = null,
                action = "HANDSHAKE_INITIATE",
                outcome = FederationOutcome.FAILURE,
                details = mapOf("targetServerUrl" to targetServerUrl),
                errorMessage = e.message,
                durationMs = duration
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
            // Verify the request signature
            val dataToVerify = "${request.serverId}|${request.serverUrl}|${request.timestamp}"
            val isValid = FederationCrypto.verify(
                dataToVerify,
                request.signature,
                FederationCrypto.pemToPublicKey(request.publicKey)
            )

            if (!isValid) {
                throw IllegalArgumentException("Invalid handshake signature")
            }

            // Check timestamp (prevent replay attacks - 5 minute window)
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(now - request.timestamp) > 5.minutes.inWholeMilliseconds) {
                throw IllegalArgumentException("Handshake request expired or timestamp invalid")
            }

            // Get our local identity
            val localIdentity = dao.getLocalServerIdentity()
                ?: throw IllegalStateException("Local server identity not initialized")

            // Create response
            val timestamp = System.currentTimeMillis()
            val agreementHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("${request.serverId}|${localIdentity.serverId}|${acceptedScopes}".toByteArray())
                .joinToString("") { "%02x".format(it) }

            val response = FederationHandshakeResponse(
                accepted = true,
                serverId = localIdentity.serverId,
                serverUrl = localIdentity.serverUrl,
                serverName = localIdentity.serverName,
                publicKey = localIdentity.publicKey,
                protocolVersion = localIdentity.protocolVersion,
                acceptedScopes = acceptedScopes,
                agreementHash = agreementHash,
                timestamp = timestamp,
                signature = "", // Will be computed below
                reason = null
            )

            // Sign the response
            val dataToSign = "${response.serverId}|${response.serverUrl}|${response.timestamp}"
            val signedResponse = response.copy(
                signature = signWithLocalKey(dataToSign)
            )

            // Store the requesting server as pending
            val server = FederatedServer(
                serverId = request.serverId,
                serverUrl = request.serverUrl,
                serverName = request.serverName,
                publicKey = request.publicKey,
                trustLevel = TrustLevel.PENDING,
                scopePermissions = acceptedScopes,
                protocolVersion = request.protocolVersion,
                isActive = true,
                federationAgreementHash = agreementHash,
                lastSyncTimestamp = null,
                serverMetadata = null,
                dataRetentionDays = 30,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
            dao.createFederatedServer(server)

            val duration = System.currentTimeMillis() - startTime
            logFederationEvent(
                eventType = FederationEventType.HANDSHAKE_ACCEPT,
                serverId = request.serverId,
                action = "HANDSHAKE_ACCEPT",
                outcome = FederationOutcome.SUCCESS,
                details = mapOf(
                    "requestingServerUrl" to request.serverUrl,
                    "acceptedScopes" to acceptedScopes.toString()
                ),
                durationMs = duration
            )

            signedResponse
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logFederationEvent(
                eventType = FederationEventType.HANDSHAKE_ACCEPT,
                serverId = request.serverId,
                action = "HANDSHAKE_ACCEPT",
                outcome = FederationOutcome.FAILURE,
                details = mapOf("requestingServerUrl" to request.serverUrl),
                errorMessage = e.message,
                durationMs = duration
            )
            throw e
        }
    }

    override suspend fun listFederatedServers(trustLevel: TrustLevel?): List<FederatedServer> {
        return dao.listFederatedServers(trustLevel)
    }

    override suspend fun updateTrustLevel(serverId: String, trustLevel: TrustLevel): Boolean {
        val startTime = System.currentTimeMillis()
        val result = dao.updateServerTrustLevel(serverId, trustLevel)

        logFederationEvent(
            eventType = FederationEventType.TRUST_LEVEL_CHANGE,
            serverId = serverId,
            action = "TRUST_UPDATE",
            outcome = if (result) FederationOutcome.SUCCESS else FederationOutcome.FAILURE,
            details = mapOf("newTrustLevel" to trustLevel.name),
            durationMs = System.currentTimeMillis() - startTime
        )

        return result
    }

    override suspend fun updateScopes(serverId: String, scopes: FederationScope): Boolean {
        return dao.updateServerScopes(serverId, scopes)
    }

    override suspend fun verifyServerSignature(
        serverId: String,
        data: String,
        signature: String
    ): Boolean {
        val server = dao.getFederatedServer(serverId) ?: return false
        return FederationCrypto.verify(data, signature, FederationCrypto.pemToPublicKey(server.publicKey))
    }

    override suspend fun signWithLocalKey(data: String): String {
        val identity = dao.getLocalServerIdentity()
            ?: throw IllegalStateException("Local server identity not initialized")
        val privateKey = FederationCrypto.pemToPrivateKey(identity.privateKey)
        return FederationCrypto.sign(data, privateKey)
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
        dao.logFederationEvent(
            eventType = eventType,
            serverId = serverId,
            action = action,
            outcome = outcome,
            details = details,
            errorMessage = errorMessage,
            durationMs = durationMs,
            remoteIp = null
        )
    }
}
