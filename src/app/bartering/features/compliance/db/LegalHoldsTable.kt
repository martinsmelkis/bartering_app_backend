package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * Legal holds prevent retention/deletion actions for specific users.
 */
object LegalHoldsTable : Table("compliance_legal_holds") {
    val id = long("id").autoIncrement()
    val userId = varchar("user_id", 255).index()
    val reason = text("reason")
    val scope = varchar("scope", 64).default("all") // all | export | deletion | retention
    val imposedBy = varchar("imposed_by", 255)
    val imposedAt = timestamp("imposed_at").default(Instant.now())
    val expiresAt = timestamp("expires_at").nullable()
    val isActive = bool("is_active").default(true)
    val releasedAt = timestamp("released_at").nullable()
    val releasedBy = varchar("released_by", 255).nullable()
    val releaseReason = text("release_reason").nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}