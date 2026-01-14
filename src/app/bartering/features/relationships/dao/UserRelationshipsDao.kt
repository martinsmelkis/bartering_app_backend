package app.bartering.features.relationships.dao

import app.bartering.features.relationships.model.*

/**
 * Data Access Object interface for user relationships operations
 */
interface UserRelationshipsDao {

    /**
     * Creates a new relationship between two users
     */
    suspend fun createRelationship(
        fromUserId: String,
        toUserId: String,
        relationshipType: RelationshipType
    ): Boolean

    /**
     * Removes a relationship between two users
     */
    suspend fun removeRelationship(
        fromUserId: String,
        toUserId: String,
        relationshipType: RelationshipType
    ): Boolean

    /**
     * Gets all relationships for a user
     */
    suspend fun getUserRelationships(userId: String): UserRelationshipsResponse

    /**
     * Gets relationships of a specific type for a user
     */
    suspend fun getRelationshipsByType(
        userId: String,
        relationshipType: RelationshipType
    ): List<String>

    /**
     * Checks if a specific relationship exists
     */
    suspend fun relationshipExists(
        fromUserId: String,
        toUserId: String,
        relationshipType: RelationshipType
    ): Boolean

    /**
     * Gets detailed relationship information with user profiles
     */
    suspend fun getRelationshipsWithProfiles(
        userId: String,
        relationshipType: RelationshipType
    ): List<RelationshipWithProfile>

    /**
     * Accepts a friend request (creates mutual FRIEND relationships)
     */
    suspend fun acceptFriendRequest(userId: String, friendUserId: String): Boolean

    /**
     * Rejects a friend request (removes the FRIEND_REQUEST_SENT)
     */
    suspend fun rejectFriendRequest(userId: String, friendUserId: String): Boolean

    /**
     * Gets relationship statistics for a user
     */
    suspend fun getRelationshipStats(userId: String): RelationshipStats

    /**
     * Checks if user A has blocked user B
     */
    suspend fun isBlocked(fromUserId: String, toUserId: String): Boolean

    /**
     * Gets all users who have blocked the specified user
     */
    suspend fun getBlockedByUsers(userId: String): List<String>
}
