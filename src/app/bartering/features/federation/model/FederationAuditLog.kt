package app.bartering.features.federation.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Audit log entry for federation events.
 * Note: This is primarily used for database operations, not API serialization.
 */
data class FederationAuditLog(
    val id: String,
    val eventType: FederationEventType,
    val serverId: String?,
    val localUserId: String?,
    val remoteUserId: String?,
    val action: String,
    val outcome: FederationOutcome,
    val details: JsonElement?, // Database-only field (JSONB), matches table type
    val errorMessage: String?,
    val remoteIp: String?,
    val durationMs: Long?,
    val timestamp: Instant
)

/**
 * Types of federation events to log.
 */
enum class FederationEventType {
    HANDSHAKE,
    HANDSHAKE_ACCEPT,
    HANDSHAKE_REJECT,
    USER_SYNC,
    USER_SEARCH,
    POSTING_SYNC,
    POSTING_SEARCH,
    MESSAGE_RELAY,
    TRUST_LEVEL_CHANGE,
    SCOPE_UPDATE,
    KEY_ROTATION,
    HEARTBEAT,
    ERROR
}

/**
 * Outcomes of federation operations.
 */
enum class FederationOutcome {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    REJECTED,
    PARTIAL
}
