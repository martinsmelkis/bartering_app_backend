package app.bartering.features.postings.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object UserPostingsTable : Table("user_postings") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val title = varchar("title", 255)
    val description = text("description")
    val value = decimal("value", 10, 2).nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val imageUrls = jsonb<List<String>>("image_urls", kotlinx.serialization.json.Json)
    val isOffer = bool("is_offer")
    val status = varchar("status", 20).default("active")
    val embedding = text("embedding").nullable() // Will be handled as raw vector string
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object PostingAttributesLinkTable : Table("posting_attributes_link") {
    val postingId = varchar("posting_id", 36).references(UserPostingsTable.id)
    val attributeId = varchar("attribute_id", 100)
    val relevancy = decimal("relevancy", 5, 4).default(1.0.toBigDecimal())

    override val primaryKey = PrimaryKey(postingId, attributeId)
}
