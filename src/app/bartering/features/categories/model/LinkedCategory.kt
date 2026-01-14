package app.bartering.features.categories.model

import java.math.BigDecimal

/**
 * Represents a category linked to an attribute, including the relevancy of that link.
 */
data class LinkedCategory(
    val categoryId: Int,
    val categoryNameKey: String,
    val parentId: Int?,
    val relevancy: BigDecimal
)