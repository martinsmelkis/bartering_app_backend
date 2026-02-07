package app.bartering.features.postings.dao

import app.bartering.features.postings.model.UserPosting
import app.bartering.features.postings.model.UserPostingRequest
import app.bartering.features.postings.model.UserPostingWithDistance

interface UserPostingDao {

    /**
     * Creates a new posting
     */
    suspend fun createPosting(userId: String, request: UserPostingRequest): String?

    /**
     * Updates an existing posting
     */
    suspend fun updatePosting(
        userId: String,
        postingId: String,
        request: UserPostingRequest
    ): Boolean

    /**
     * Deletes a posting (soft delete by setting status to DELETED)
     */
    suspend fun deletePosting(userId: String, postingId: String): Boolean

    /**
     * Gets a specific posting by ID
     */
    suspend fun getPosting(postingId: String): UserPosting?

    /**
     * Gets all postings by a specific user
     */
    suspend fun getUserPostings(userId: String, includeExpired: Boolean = false): List<UserPosting>

    /**
     * Gets all postings (across all users)
     */
    suspend fun getAllPostings(includeExpired: Boolean = false): List<UserPosting>

    /**
     * Gets nearby postings based on location
     */
    suspend fun getNearbyPostings(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        isOffer: Boolean? = null,
        excludeUserId: String? = null,
        limit: Int = 50
    ): List<UserPostingWithDistance>

    /**
     * Searches postings by keyword using semantic similarity
     */
    suspend fun searchPostings(
        searchText: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null,
        isOffer: Boolean? = null,
        limit: Int = 50
    ): List<UserPostingWithDistance>

    /**
     * Gets postings that match a user's interests/needs
     * If the user has interests, this returns offers that match those interests
     * If the user has offers, this returns interests that match those offers
     */
    suspend fun getMatchingPostings(
        userId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null,
        limit: Int = 50
    ): List<UserPostingWithDistance>

    /**
     * Updates the semantic embedding for a posting
     */
    suspend fun updatePostingEmbedding(postingId: String): Boolean

    /**
     * Marks expired postings as expired based on expires_at timestamp
     */
    suspend fun markExpiredPostings(): Int
    
    /**
     * Permanently deletes postings that have been expired for more than the grace period
     * This is a hard delete that removes the posting from the database permanently
     * @param gracePeriodDays Number of days a posting must be expired before hard deletion
     * @return Number of postings permanently deleted
     */
    suspend fun hardDeleteExpiredPostings(gracePeriodDays: Int = 30): Int
}
