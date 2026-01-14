package app.bartering.features.attributes.db

import com.pgvector.PGvector
import app.bartering.model.VectorColumnType
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

fun AttributesMasterTable.embeddingField(name: String, dimensions: Int): Column<PGvector> =
    registerColumn(name, VectorColumnType(dimensions)
)

/**
 * In a barter app, an interest can implicitly be an offer. If I am interested in "playing chess,"
 * I am also implicitly offering to play chess with someone. A rigid separation between what I want
 * and what I have fails to capture this duality.
 *
 * Instead of thinking in terms of "Wants" and "Haves," we can think in terms of a user's
 * "Attributes" or "Skills & Interests." Or even "Possessions".
 * Each attribute has a property defining the user's relationship to it.
 *
 * This table includes both interests and offerings. It's a global dictionary of every possible
 * skill, hobby, or physical object in the app.
 *
 * val id = integer("id").autoIncrement() and val primaryKey = PrimaryKey(id) is added by Base class
 **/
object AttributesMasterTable : IntIdTable("attributes") {
    val attributeNameKey = varchar("attribute_key", 100).uniqueIndex() // e.g., "graphic_design_service", "handmade_pottery"
    val localizationKey = varchar("localization_key", 150).uniqueIndex()

    // The embedding vector, used by semantic search and matching
    val embedding = embeddingField("embedding", 1024).nullable()
    // The category information is in the AttributeCategoriesLinkTable join table.

    // The following are for sorting, organization, business intelligence
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    // For custom, user-added attribute types
    val isApproved = bool("is_approved").default(false)
    val customUserAttrText = varchar("custom_user_attr_text", 100)
}
