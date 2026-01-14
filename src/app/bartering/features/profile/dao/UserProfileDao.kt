package app.bartering.features.profile.dao

import app.bartering.features.attributes.model.UserAttributeType
import app.bartering.features.authentication.model.UserRegistrationDataDto
import app.bartering.features.profile.model.UserProfile
import app.bartering.features.profile.model.UserProfileUpdateRequest
import app.bartering.features.profile.model.UserProfileWithDistance

interface UserProfileDao {

    suspend fun createProfile(user: UserRegistrationDataDto)

    suspend fun getProfile(userId: String): UserProfile?

    suspend fun getUserPublicKeyById(id: String): String?

    suspend fun updateProfile(userId: String, request: UserProfileUpdateRequest): String

    suspend fun getNearbyProfiles(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        excludeUserId: String? = null): List<UserProfileWithDistance>

    suspend fun getSimilarProfiles(
        userId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null): List<UserProfileWithDistance>

    suspend fun getHelpfulProfiles(
        userId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null): List<UserProfileWithDistance>

    suspend fun updateSemanticProfile(userId: String, attributeType: UserAttributeType)

    suspend fun searchProfilesByKeyword(
        userId: String,
        searchText: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null,
        limit: Int = 20,
        customWeight: Int = 50
    ): List<UserProfileWithDistance>

    suspend fun getUserCreatedAt(userId: String): java.time.Instant?
    
    /**
     * Gets all users with pagination support.
     * Optionally filters by federation_enabled flag and updated since timestamp.
     * Used for federation user sync.
     */
    suspend fun getAllUsers(
        federationEnabled: Boolean = true,
        updatedSince: java.time.Instant? = null,
        page: Int = 0,
        pageSize: Int = 50
    ): Pair<List<UserProfile>, Int> // Returns (users, totalCount)
}