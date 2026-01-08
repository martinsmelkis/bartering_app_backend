package org.barter.features.reviews.db

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import java.time.Instant

/**
 * Stores detected suspicious patterns for monitoring and investigation.
 */
object RiskPatternsTable : Table("review_risk_patterns") {
    val id = varchar("id", 36)
    val patternType = varchar("pattern_type", 50).index() // device_sharing, wash_trading, etc.
    val severity = varchar("severity", 20) // info, low, medium, high, critical
    val description = text("description")
    val affectedUsers = jsonb(
        "affected_users",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<List<String>>(it) }
    )
    val evidence = jsonb(
        "evidence",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<Map<String, String>>(it) }
    )
    val detectedAt = timestamp("detected_at").default(Instant.now()).index()

    // pending, investigating, resolved, false_positive
    val status = varchar("status", 20).default("pending")
    val resolvedAt = timestamp("resolved_at").nullable()
    val reviewedBy = varchar("reviewed_by", 255).nullable() // moderator user ID
    val notes = text("notes").nullable()
    
    init {
        index(isUnique = false, patternType, severity)
        index(isUnique = false, status, detectedAt)
    }

    override val primaryKey = PrimaryKey(id)
}
