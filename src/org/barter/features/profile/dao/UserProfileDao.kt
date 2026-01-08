package org.barter.features.profile.dao

import org.barter.features.attributes.model.UserAttributeType
import org.barter.features.authentication.model.UserRegistrationDataDto
import org.barter.features.profile.model.UserProfile
import org.barter.features.profile.model.UserProfileUpdateRequest
import org.barter.features.profile.model.UserProfileWithDistance

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
}