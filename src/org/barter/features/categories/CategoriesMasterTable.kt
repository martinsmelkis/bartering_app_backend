package org.barter.features.categories

import com.pgvector.PGvector
import org.barter.features.attributes.db.AttributesMasterTable
import org.barter.model.VectorColumnType
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption

fun CategoriesMasterTable.embeddingField(name: String, dimensions: Int): Column<PGvector> =
    registerColumn(name, VectorColumnType(dimensions)
)

/**
 *
 * Defines the Master list of categories that attributes can belong to.
 * Defines a hierarchical structure for categories.
 *
 * A category can be a main category (e.g., "Nature") or a sub-category
 * (e.g., "Gardening," which belongs to "Nature").
 */
object CategoriesMasterTable : IntIdTable("categories") {
    // A machine-readable key for the category (e.g., "main_nature", "sub_gardening").
    val categoryNameKey = varchar("category_key", 100).uniqueIndex()
    val localizationKey = varchar("localization_key", 150).uniqueIndex()

    val description = text("description")

    val embedding = embeddingField("embedding", 1024).nullable()

    // Self-referencing foreign key to establish hierarchy.
    // This column will be NULL for the 7 main categories/groups.
    // It will contain the ID of the parent for sub-categories.
    val parentId = integer("parent_id").references(id, onDelete = ReferenceOption.SET_NULL).nullable()

    // UI styling hint for client-side rendering (e.g., "icon_nature", "color_blue", etc.)
    val uiStyleHint = varchar("ui_style_hint", 100).nullable()
}