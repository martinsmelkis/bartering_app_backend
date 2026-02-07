package app.bartering.features.attributes.dao

import app.bartering.features.attributes.model.Attribute
import app.bartering.features.attributes.model.AttributeSuggestion

/**
 * Data Access Object for managing the master list of attributes.
 */
interface AttributesDao {

    /**
     * Finds an attribute by its unique key. If it doesn't exist, it creates a new one.
     * This is the core function for supporting user-generated custom attributes.
     * @param attributeNameKey The machine-readable key (e.g., "board_games").
     * @param isApproved Whether the attribute should be approved (true for ExpandedInterests, false for user-generated).
     * @return The existing or newly created Attribute data object.
     */
    suspend fun findOrCreate(attributeNameKey: String, isApproved: Boolean = false): Attribute?

    suspend fun getComplementaryInterestSuggestions(
        profileKeywords: Map<String, Double>,
        limit: Int = 15,
        userId: String
    ): List<AttributeSuggestion>

    suspend fun parseInterestSuggestionsFromOnboardingData(
        havesKeywords: Map<String, Double>,
        userId: String,
        limit: Int
    ): List<AttributeSuggestion>

    suspend fun populateMissingEmbeddings()

}