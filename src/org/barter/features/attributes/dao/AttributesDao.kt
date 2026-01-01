package org.barter.features.attributes.dao

import org.barter.features.attributes.model.Attribute
import org.barter.features.attributes.model.AttributeSuggestion

/**
 * Data Access Object for managing the master list of attributes.
 */
interface AttributesDao {

    /**
     * Finds an attribute by its unique key. If it doesn't exist, it creates a new, unapproved one.
     * This is the core function for supporting user-generated custom attributes.
     * @param attributeNameKey The machine-readable key (e.g., "board_games").
     * @return The existing or newly created Attribute data object.
     */
    suspend fun findOrCreate(attributeNameKey: String): Attribute?

    suspend fun findSimilarInterestsForProfile(
        profileKeywords: Map<String, Double>,
        limit: Int = 15,
        userId: String
    ): List<AttributeSuggestion>

    suspend fun findComplementaryInterests(
        havesKeywords: Map<String, Double>,
        userId: String,
        limit: Int
    ): List<AttributeSuggestion>

    suspend fun populateMissingEmbeddings()

}