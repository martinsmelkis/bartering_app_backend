package app.bartering.features.reviews.db

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import java.time.Instant

/**
 * Unified moderation audit table for review appeals and user report moderation lifecycle events.
 */
object ModerationAuditLogTable : Table("moderation_audit_log") {
    val id = varchar("id", 36)
    val eventType = varchar("event_type", 80)
    val entityType = varchar("entity_type", 80).nullable()
    val entityId = varchar("entity_id", 36).nullable()

    val reviewId = varchar("review_id", 36).nullable().index()
    val transactionId = varchar("transaction_id", 36).nullable().index()

    val actorUserId = varchar("actor_user_id", 255).nullable().index()
    val targetUserId = varchar("target_user_id", 255).nullable().index()
    val assignedTo = varchar("assigned_to", 255).nullable().index()

    val status = varchar("status", 50).nullable()
    val priority = varchar("priority", 50).nullable()

    val metadata = jsonb(
        "metadata",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, String>>(it) }
    ).nullable()

    val createdAt = timestamp("created_at").default(Instant.now()).index()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_moderation_audit_entity", false, entityType, entityId)
        index("idx_moderation_audit_status", false, status, priority, createdAt)
    }
}
