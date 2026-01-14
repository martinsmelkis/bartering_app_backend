package app.bartering.features.federation.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Stores information about federated servers that this instance trusts and communicates with.
 * Each server has cryptographic keys for verification and configurable trust scopes.
 */
object FederatedServersTable : Table("federated_servers") {
    
    /** Unique identifier for the federated server (UUID format) */
    val serverId = varchar("server_id", 36)
    
    /** Base URL of the federated server (e.g., "https://barter.example.com") */
    val serverUrl = varchar("server_url", 255)
    
    /** Display name for the server (e.g., "Portland Barter Hub") */
    val serverName = varchar("server_name", 255).nullable()
    
    /** Public key of the remote server (PEM format) for signature verification */
    val publicKey = text("public_key")
    
    /** Trust level: FULL, PARTIAL, PENDING, BLOCKED */
    val trustLevel = varchar("trust_level", 20).default("PENDING")
    
    /** 
     * Defines what data/features are shared with this server.
     * Schema: { users: boolean, postings: boolean, chat: boolean, geolocation: boolean, attributes: boolean }
     */
    val scopePermissions = jsonb(
        name = "scope_permissions",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, Boolean>>(it) }
    )
    
    /** Hash of the federation agreement signed by both servers */
    val federationAgreementHash = varchar("federation_agreement_hash", 255).nullable()
    
    /** Last successful sync/heartbeat timestamp */
    val lastSyncTimestamp = timestamp("last_sync_timestamp").nullable()
    
    /** Additional server metadata (location, admin contact, version, etc.) stored as JSON */
    val serverMetadata = jsonb(
        name = "server_metadata",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, String>>(it) }
    ).nullable()
    
    /** Protocol version this server supports (e.g., "1.0", "1.1") */
    val protocolVersion = varchar("protocol_version", 10).default("1.0")
    
    /** Whether this server is currently active/reachable */
    val isActive = bool("is_active").default(true)
    
    /** Data retention policy in days (how long to cache remote data) */
    val dataRetentionDays = integer("data_retention_days").default(30)
    
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(serverId)
}
