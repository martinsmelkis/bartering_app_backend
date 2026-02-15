package app.bartering.features.attributes.dao

import app.bartering.features.attributes.model.UserAttributeType
import java.math.BigDecimal

/**
 * Data Access Object for managing the link between users and their attributes.
 */
interface UserAttributesDao {
    /**
     * Creates a new link between a user and an attribute.
     * @param userId The ID of the user.
     * @param attributeId The ID of the attribute from the master table.
     * @param type The relationship type (SEEKING, PROVIDING, SHARING).
     * @param relevancy The calculated importance of this attribute to the user.
     * @param description Optional text providing more context.
     */
    suspend fun create(
        userId: String,
        attributeId: String,
        type: UserAttributeType,
        relevancy: BigDecimal,
        description: String?
    )

    /**
     * Deletes all attributes of a specific type for a given user.
     * This is useful for resetting a user's interests before applying a new set.
     * @param userId The ID of the user.
     * @param type The type of attribute to delete (e.g., all SEEKING attributes).
     * @return The number of rows deleted.
     */
    suspend fun deleteUserAttributesByType(userId: String, type: UserAttributeType): Int
}
