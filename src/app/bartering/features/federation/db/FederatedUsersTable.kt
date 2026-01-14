package app.bartering.features.federation.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Maps remote users from federated servers to their cached local representation.
 * Allows local users to discover and interact with users on other servers.
 */
object FederatedUsersTable : Table("federated_users") {
    
    /** Local user ID if this is a mirrored/synced user, null for remote-only references */
    val localUserId = varchar("local_user_id", 255).references(UserRegistrationDataTable.id).nullable()
    
    /** User ID on the remote/origin server */
    val remoteUserId = varchar("remote_user_id", 255)
    
    /** Reference to the server this user originates from */
    val originServerId = varchar("origin_server_id", 36).references(FederatedServersTable.serverId)
    
    /** Full remote user identifier (e.g., "user123@barter.example.com") for ActivityPub-style addressing */
    val federatedUserId = varchar("federated_user_id", 512)
    
    /** 
     * Cached snapshot of user's profile data from remote server.
     * Includes: name, location (if permitted), bio, profileImageUrl, etc.
     */
    val cachedProfileData = jsonb(
        name = "cached_profile_data",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, Any?>>(it) }
    ).nullable()
    
    /** Public key of the remote user for E2E encrypted messaging */
    val publicKey = text("public_key").nullable()
    
    /** Whether this user has federation enabled on their profile */
    val federationEnabled = bool("federation_enabled").default(true)
    
    /** Last time this user's data was synced/updated from origin server */
    val lastUpdated = timestamp("last_updated").default(Instant.now())
    
    /** Last time this remote user was seen online (if shared by origin server) */
    val lastOnline = timestamp("last_online").nullable()
    
    /** Expiration timestamp for this cached data */
    val expiresAt = timestamp("expires_at").nullable()
    
    val createdAt = timestamp("created_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(remoteUserId, originServerId)
}
