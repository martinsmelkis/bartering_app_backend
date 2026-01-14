package app.bartering.features.federation.db

import app.bartering.features.postings.db.UserPostingsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Stores cached postings from federated servers to enable cross-server marketplace search.
 */
object FederatedPostingsTable : Table("federated_postings") {
    
    /** Local posting ID if synced/mirrored locally, null for remote-only references */
    val localPostingId = varchar("local_posting_id", 36).references(UserPostingsTable.id).nullable()
    
    /** Posting ID on the remote/origin server */
    val remotePostingId = varchar("remote_posting_id", 36)
    
    /** Reference to the server this posting originates from */
    val originServerId = varchar("origin_server_id", 36).references(FederatedServersTable.serverId)
    
    /** The remote user who created this posting */
    val remoteUserId = varchar("remote_user_id", 255)
    
    /** 
     * Cached posting data from remote server.
     * Includes: title, description, value, imageUrls, isOffer, status, embedding, attributes
     */
    val cachedData = jsonb(
        name = "cached_data",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, Any?>>(it) }
    )
    
    /** URL to view this posting on the origin server */
    val remoteUrl = varchar("remote_url", 512).nullable()
    
    /** Whether this posting is still active on the remote server */
    val isActive = bool("is_active").default(true)
    
    /** When this posting expires on the remote server */
    val expiresAt = timestamp("expires_at").nullable()
    
    /** Last time this posting was synced from the origin server */
    val lastSynced = timestamp("last_synced").default(Instant.now())
    
    /** Hash of the cached data to detect changes */
    val dataHash = varchar("data_hash", 64).nullable()
    
    val createdAt = timestamp("created_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(remotePostingId, originServerId)
}
