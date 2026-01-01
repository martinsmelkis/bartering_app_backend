package org.barter.features.categories.dao

import org.barter.features.ai.CategoryWithDescription
import org.barter.features.attributes.model.CategoryLink
import org.barter.features.categories.model.LinkedCategory

/**
 * Data Access Object for managing the hierarchical categories.
 */
interface CategoriesDao {
    /**
     * Finds a category by its unique key. If it doesn't exist, it creates a new one.
     * This is useful for idempotent operations, like seeding the database.
     *
     * @param categoryKey The machine-readable key (e.g., "general_legacy").
     * @param localizationKey The localization key to assign if the category is new.
     * @param parentId The optional parent category ID for creating sub-categories.
     * @return The existing or newly created Category data object.
     */
    suspend fun findOrCreate(
        categoryKey: String,
        localizationKey: String,
        parentId: Int? = null,
    ): LinkedCategory

    /**
     * For a given list of attribute keys, finds the CategoryLink of their main parent category.
     * @return A list of CategoryLink.
     */
    suspend fun findMainCategoryForAttributes(attributeKeys: List<String>): List<CategoryLink>

    suspend fun findAllMainCategoriesWithDescriptions(): List<CategoryWithDescription>

}