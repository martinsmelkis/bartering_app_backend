package app.bartering.features.federation.dao

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.federation.db.FederatedUsersTable
import app.bartering.features.federation.model.CachedFederatedProfileData
import app.bartering.features.federation.model.FederatedUser
import app.bartering.features.federation.model.FederatedUserProfile
import app.bartering.features.profile.dao.UserProfileDao
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.time.Instant

class FederatedUserDaoImpl(
    private val userProfileDao: UserProfileDao
) : FederatedUserDao {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun upsertFederatedUser(
        remoteUserId: String,
        originServerId: String,
        federatedUserId: String,
        profileData: FederatedUserProfile,
        publicKey: String?,
        expiresAt: Instant?
    ): Boolean = dbQuery {
        try {
            // Convert profile data to serializable cached format
            val cachedData = CachedFederatedProfileData(
                userId = profileData.userId,
                name = profileData.name,
                bio = profileData.bio,
                profileImageUrl = profileData.profileImageUrl,
                location = profileData.location,
                attributes = profileData.attributes,
                lastOnline = profileData.lastOnline?.toString(),
                publicKey = publicKey
            )
            
            // Check if user already exists
            val existing = FederatedUsersTable.selectAll()
                .where { 
                    (FederatedUsersTable.remoteUserId eq remoteUserId) and 
                    (FederatedUsersTable.originServerId eq originServerId) 
                }
                .singleOrNull()
            
            if (existing != null) {
                // Update existing
                FederatedUsersTable.update({
                    (FederatedUsersTable.remoteUserId eq remoteUserId) and 
                    (FederatedUsersTable.originServerId eq originServerId)
                }) {
                    it[FederatedUsersTable.federatedUserId] = federatedUserId
                    it[cachedProfileData] = cachedData
                    it[FederatedUsersTable.publicKey] = publicKey
                    it[FederatedUsersTable.lastUpdated] = Instant.now()
                    it[FederatedUsersTable.lastOnline] = profileData.lastOnline
                    it[FederatedUsersTable.expiresAt] = expiresAt
                }
            } else {
                // Insert new
                FederatedUsersTable.insert {
                    it[FederatedUsersTable.remoteUserId] = remoteUserId
                    it[FederatedUsersTable.originServerId] = originServerId
                    it[FederatedUsersTable.federatedUserId] = federatedUserId
                    it[FederatedUsersTable.cachedProfileData] = cachedData
                    it[FederatedUsersTable.publicKey] = publicKey
                    it[FederatedUsersTable.lastUpdated] = Instant.now()
                    it[FederatedUsersTable.lastOnline] = profileData.lastOnline
                    it[FederatedUsersTable.expiresAt] = expiresAt
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    override suspend fun getFederatedUser(remoteUserId: String, originServerId: String): FederatedUser? = dbQuery {
        FederatedUsersTable.selectAll()
            .where {
                (FederatedUsersTable.remoteUserId eq remoteUserId) and
                (FederatedUsersTable.originServerId eq originServerId)
            }
            .map { rowToFederatedUser(it) }
            .singleOrNull()
    }
    
    override suspend fun getFederatedUsersFromServer(originServerId: String, limit: Int): List<FederatedUser> = dbQuery {
        FederatedUsersTable.selectAll()
            .where { FederatedUsersTable.originServerId eq originServerId }
            .limit(limit)
            .map { rowToFederatedUser(it) }
    }
    
    override suspend fun getStaleUsers(olderThan: Instant): List<FederatedUser> = dbQuery {
        FederatedUsersTable.selectAll()
            .where { FederatedUsersTable.lastUpdated less olderThan }
            .map { rowToFederatedUser(it) }
    }
    
    override suspend fun deleteFederatedUsersFromServer(originServerId: String): Int = dbQuery {
        FederatedUsersTable.deleteWhere { 
            FederatedUsersTable.originServerId eq originServerId 
        }
    }
    
    override suspend fun getLocalUsersForSync(
        page: Int,
        pageSize: Int,
        updatedSince: Instant?
    ): Pair<List<FederatedUserProfile>, Int> {
        // Get local users that have federation enabled
        val (userProfiles, totalCount) = userProfileDao.getAllUsers(
            federationEnabled = true,
            updatedSince = updatedSince,
            page = page,
            pageSize = pageSize
        )
        
        // Convert UserProfile to FederatedUserProfile
        val federatedProfiles = userProfiles.map { profile ->
            // Get public key for federated chat
            val publicKey = userProfileDao.getUserPublicKeyById(profile.userId)
            
            FederatedUserProfile(
                userId = profile.userId,
                name = profile.name,
                bio = null, // Not exposing bio in federation for privacy
                profileImageUrl = null, // Not exposing images for bandwidth/privacy
                location = if (profile.latitude != null && profile.longitude != null) {
                    app.bartering.features.federation.model.FederatedLocation(
                        lat = profile.latitude!!,
                        lon = profile.longitude!!,
                        city = null, // Not implemented yet
                        country = null // Not implemented yet
                    )
                } else null,
                attributes = profile.attributes.map {
                    app.bartering.features.federation.model.FederatedAttribute(
                        attributeId = it.attributeId,
                        type = it.type,
                        relevancy = it.relevancy
                    )
                },
                lastOnline = try {
                    app.bartering.features.profile.cache.UserActivityCache.getLastSeen(profile.userId)
                        ?.let { Instant.ofEpochMilli(it) }
                } catch (e: Exception) {
                    null
                },
                publicKey = publicKey // Include for E2E encrypted chat
            )
        }
        
        return Pair(federatedProfiles, totalCount)
    }
    
    private fun rowToFederatedUser(row: ResultRow): FederatedUser {
        return FederatedUser(
            localUserId = row[FederatedUsersTable.localUserId],
            remoteUserId = row[FederatedUsersTable.remoteUserId],
            originServerId = row[FederatedUsersTable.originServerId],
            federatedUserId = row[FederatedUsersTable.federatedUserId],
            cachedProfileData = row[FederatedUsersTable.cachedProfileData],
            publicKey = row[FederatedUsersTable.publicKey],
            federationEnabled = row[FederatedUsersTable.federationEnabled],
            lastUpdated = row[FederatedUsersTable.lastUpdated],
            lastOnline = row[FederatedUsersTable.lastOnline],
            expiresAt = row[FederatedUsersTable.expiresAt],
            createdAt = row[FederatedUsersTable.createdAt]
        )
    }
}
