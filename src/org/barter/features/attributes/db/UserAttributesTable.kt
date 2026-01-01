package org.barter.features.attributes.db

import org.barter.features.attributes.model.UserAttributeType
import org.barter.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * This table links a user to an attribute and, crucially, defines their relationship to it.
 *
 * Let's use the "playing chess" example:1.The Attribute: In the Attributes table, there is an entry
 * with attribute_key = "playing_chess". 2.User A's Goal: User A wants to find a chess partner. They
 * add "Playing Chess" to their profile. 3.The Data Entry: In your backend, you create a record in
 * UserAttributes:◦userId: "user_A_id"◦attributeId: (the ID for "playing_chess")◦type:
 * UserAttributeType.SHARING◦relevancy: 0.9 (User A is very keen)◦description:
 * "Looking for a friendly game on evenings or weekends. I'm an intermediate player."
 */
object UserAttributesTable : Table("user_attributes") {
    // --- Core Links --- (Composite Primary Key)
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val attributeId = varchar("attribute_id", 100)
        //.uniqueIndex()
        .references(AttributesMasterTable.attributeNameKey) // Links to the *type* of attribute

    // --- Attribute-Specific Details ---
    val description = text("description").nullable() // Detailed description of the user's attribute.

    // --- Core Relationship Definers ---
    val type = enumerationByName("type", 15, UserAttributeType::class)
    val relevancy = decimal("relevancy", 5, 4) // User's skill/interest level (e.g., 0.9500)

    // Estimated monetary value. Useful for reference in barter ("trade items of similar value").
    val estimatedValue = decimal("estimated_value", 10, 2).nullable()

    // --- Timestamps ---
    // The timestamp when this offer expires and should no longer be shown.
    // A null value implies the offer is long-term or indefinite (e.g., a skill).
    val expiresAt = timestamp("expires_at").nullable()

    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(userId, attributeId, type)
}
