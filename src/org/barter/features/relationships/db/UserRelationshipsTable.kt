package org.barter.features.relationships.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for storing user-to-user relationships.
 * Corresponds to the 'user_relationships' table in the database.
 */
object UserRelationshipsTable : Table("user_relationships") {
    val userIdFrom = varchar("user_id_from", 255)
    val userIdTo = varchar("user_id_to", 255)
    val relationshipType = varchar("relationship_type", 50)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(userIdFrom, userIdTo, relationshipType)
}
