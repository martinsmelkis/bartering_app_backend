package app.bartering.features.federation.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Audit log for all federation-related activities.
 * Critical for security, debugging, and compliance (GDPR, etc.)
 */
object FederationAuditLogTable : Table("federation_audit_log") {
    
    val id = varchar("id", 36)
    
    /** Type of event: HANDSHAKE, USER_SYNC, POSTING_SYNC, MESSAGE_RELAY, TRUST_CHANGE, etc. */
    val eventType = varchar("event_type", 50)
    
    /** Server involved in this event */
    val serverId = varchar("server_id", 36).references(FederatedServersTable.serverId).nullable()
    
    /** Local user involved (if applicable) */
    val localUserId = varchar("local_user_id", 255).nullable()
    
    /** Remote user involved (if applicable) */
    val remoteUserId = varchar("remote_user_id", 255).nullable()
    
    /** Action performed: REQUEST, RESPONSE, SYNC, VERIFY, REJECT, etc. */
    val action = varchar("action", 50)
    
    /** Outcome: SUCCESS, FAILURE, TIMEOUT, REJECTED */
    val outcome = varchar("outcome", 20)
    
    /** 
     * Additional details about the event stored as JSON.
     * May include: request parameters, error messages, data sizes, etc.
     */
    val details = jsonb(
        name = "details",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, Any?>>(it) }
    ).nullable()
    
    /** Error message if outcome was FAILURE */
    val errorMessage = text("error_message").nullable()
    
    /** IP address of the federated server that made the request */
    val remoteIp = varchar("remote_ip", 45).nullable()
    
    /** Duration of the operation in milliseconds */
    val durationMs = long("duration_ms").nullable()
    
    val timestamp = timestamp("timestamp").default(Instant.now())
    
    override val primaryKey = PrimaryKey(id)
}
