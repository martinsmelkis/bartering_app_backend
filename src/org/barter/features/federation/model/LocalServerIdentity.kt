package org.barter.features.federation.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Represents the identity of THIS local server instance.
 * Contains cryptographic keys and metadata for federation.
 * Note: This is primarily used for database operations, not API serialization.
 */
data class LocalServerIdentity(
    val serverId: String,
    val serverUrl: String,
    val serverName: String,
    val publicKey: String,
    val privateKey: String, // Should be encrypted at rest and never exposed via API
    val keyAlgorithm: String = "RSA",
    val keySize: Int = 2048,
    val protocolVersion: String = "1.0",
    val adminContact: String?,
    val description: String?,
    val locationHint: String?,
    val keyGeneratedAt: Instant,
    val keyRotationDue: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /**
     * Returns a public-facing version without the private key.
     */
    fun toPublicIdentity(): PublicServerIdentity {
        return PublicServerIdentity(
            serverId = serverId,
            serverUrl = serverUrl,
            serverName = serverName,
            publicKey = publicKey,
            protocolVersion = protocolVersion,
            adminContact = adminContact,
            description = description,
            locationHint = locationHint
        )
    }
}

/**
 * Public-facing server identity (safe to share with other servers).
 */
@Serializable
data class PublicServerIdentity(
    val serverId: String,
    val serverUrl: String,
    val serverName: String,
    val publicKey: String,
    val protocolVersion: String,
    val adminContact: String?,
    val description: String?,
    val locationHint: String?
)
