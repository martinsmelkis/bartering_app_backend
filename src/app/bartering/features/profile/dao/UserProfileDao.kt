package app.bartering.features.profile.dao

import app.bartering.features.attributes.model.UserAttributeType
import app.bartering.features.authentication.model.UserRegistrationDataDto
import app.bartering.features.profile.model.UserProfile
import app.bartering.features.profile.model.UserProfileUpdateRequest
import app.bartering.features.profile.model.UserProfileExtended

interface UserProfileDao {

    suspend fun createProfile(user: UserRegistrationDataDto)

    suspend fun getProfile(userId: String): UserProfile?

    suspend fun getUserPublicKeyById(id: String): String?

    suspend fun updateProfile(userId: String, request: UserProfileUpdateRequest): String

    suspend fun getNearbyProfiles(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        excludeUserId: String? = null): List<UserProfileExtended>

    suspend fun getSimilarProfiles(
        userId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null): List<UserProfileExtended>

    suspend fun getHelpfulProfiles(
        userId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null): List<UserProfileExtended>

    suspend fun updateSemanticProfile(userId: String, attributeType: UserAttributeType)

    suspend fun searchProfilesByKeyword(
        userId: String,
        searchText: String,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null,
        limit: Int = 20,
        customWeight: Int = 50,
        seeking: Boolean? = null,
        offering: Boolean? = null
    ): List<UserProfileExtended>

    suspend fun getUserCreatedAt(userId: String): java.time.Instant?

    /**
     * Updates the user's public key in user_registration_data.
     * Used after device migration to update the signing key.
     */
    suspend fun updateUserPublicKey(userId: String, publicKey: String): Boolean

    /**
     * Returns whether user has granted location processing consent.
     */
    suspend fun hasLocationConsent(userId: String): Boolean

    /**
     * Returns whether user has granted AI processing consent.
     */
    suspend fun hasAiProcessingConsent(userId: String): Boolean

    /**
     * Returns whether user has granted analytics consent.
     */
    suspend fun hasAnalyticsConsent(userId: String): Boolean

    /**
     * Gets all users with pagination support.
     * Returns only users with `user_privacy_consents.federation_consent = true`.
     * Used for federation user sync.
     */
    suspend fun getAllUsers(
        updatedSince: java.time.Instant? = null,
        page: Int = 0,
        pageSize: Int = 50
    ): Pair<List<UserProfile>, Int> // Returns (users, totalCount)
}