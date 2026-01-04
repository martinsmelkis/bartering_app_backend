package org.barter.features.reviews.db

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import java.time.Instant

/**
 * Audit trail for review-related actions.
 * Used for abuse detection and pattern analysis.
 */
object ReviewAuditLogTable : Table("review_audit_log") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).index()
    val action = varchar("action", 50) // review_submitted, review_edited, appeal_filed, etc.
    val relatedReviewId = varchar("related_review_id", 36).nullable().index()
    val metadata = jsonb(
        "metadata",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, String>>(it) }
    )
    val ipAddress = varchar("ip_address", 45).nullable()
    val deviceFingerprint = varchar("device_fingerprint", 255).nullable().index()
    val timestamp = timestamp("timestamp").default(Instant.now()).index()

    override val primaryKey = PrimaryKey(id)
}
