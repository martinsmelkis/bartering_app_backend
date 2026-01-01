package org.barter.features.categories

import org.barter.features.attributes.db.AttributesMasterTable
import org.jetbrains.exposed.sql.Table

/**
 * A join table to create a many-to-many relationship between Attributes and Categories.
 * This allows an attribute to belong to multiple categories, each with a specific relevancy.
 *
 *  It links an attribute to a category's id, category could be a parent or a child.
 */
object AttributeCategoriesLinkTable : Table("attribute_categories_link") {
    val attributeId = integer("attribute_id").references(AttributesMasterTable.id)
    val categoryId = integer("category_id").references(CategoriesMasterTable.id)

    // The relevancy of THIS SPECIFIC attribute to THIS SPECIFIC category.
    // e.g., "Photography" is 0.9 relevant to "Creative Services" but maybe 0.6 to "Hobbies".
    val relevancy = decimal("relevancy", 5, 4)

    // A composite primary key ensures an attribute can only be linked to a category once.
    override val primaryKey = PrimaryKey(attributeId, categoryId)
}