package app.bartering.features.federation.dao

import app.bartering.features.federation.model.FederatedUser
import app.bartering.features.federation.model.FederatedUserProfile
import java.time.Instant

/**
 * Data Access Object for federated user operations.
 */
interface FederatedUserDao {
    
    /**
     * Stores or updates a federated user in the cache.
     */
    suspend fun upsertFederatedUser(
        remoteUserId: String,
        originServerId: String,
        federatedUserId: String,
        profileData: FederatedUserProfile,
        publicKey: String?,
        expiresAt: Instant?
    ): Boolean
    
    /**
     * Gets a federated user by their remote ID and origin server.
     */
    suspend fun getFederatedUser(remoteUserId: String, originServerId: String): FederatedUser?
    
    /**
     * Gets all federated users from a specific server.
     */
    suspend fun getFederatedUsersFromServer(originServerId: String, limit: Int = 100): List<FederatedUser>
    
    /**
     * Gets federated users that need refresh (expired or old).
     */
    suspend fun getStaleUsers(olderThan: Instant): List<FederatedUser>
    
    /**
     * Deletes federated users from a specific server (when unfederating).
     */
    suspend fun deleteFederatedUsersFromServer(originServerId: String): Int
    
    /**
     * Gets paginated list of users from origin server for syncing.
     * Only returns users that are federation-enabled.
     */
    suspend fun getLocalUsersForSync(
        page: Int,
        pageSize: Int,
        updatedSince: Instant?
    ): Pair<List<FederatedUserProfile>, Int> // Returns (users, totalCount)
}
