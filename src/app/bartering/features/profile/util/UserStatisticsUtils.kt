package app.bartering.features.profile.util

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory

/**
 * Utility class for executing user statistics and counting queries.
 *
 * This class contains reusable database query methods for counting users
 * based on location, activity status, and other criteria.
 * All methods are suspend functions that execute within database transactions.
 */
object UserStatisticsUtils {

    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Counts the number of active users within a geographic radius.
     *
     * Used to dynamically adjust search thresholds based on user density.
     * In areas with fewer users, the algorithm becomes more permissive
     * to ensure meaningful match results.
     *
     * @param userId User ID to exclude from count (the searching user)
     * @param latitude Search center latitude
     * @param longitude Search center longitude
     * @param radiusMeters Search radius in meters
     * @return Count of users within radius, or 0 if error occurs
     */
    suspend fun countNearbyUsers(
        userId: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double
    ): Int {
        return try {
            val query = """
                SELECT COUNT(DISTINCT u.id) as user_count
                FROM user_registration_data u
                INNER JOIN user_profiles up ON u.id = up.user_id
                WHERE u.id != ?
                    AND ST_DWithin(
                        up.location::geography,
                        ST_MakePoint(?, ?)::geography,
                        ?
                    )
            """.trimIndent()

            TransactionManager.current().connection.prepareStatement(query, false)
                .also { statement ->
                    statement[1] = userId
                    statement[2] = longitude
                    statement[3] = latitude
                    statement[4] = radiusMeters
                    val rs = statement.executeQuery()
                    if (rs.next()) {
                        return rs.getInt("user_count")
                    }
                }
            0
        } catch (e: Exception) {
            log.error("Error counting nearby users for user {}: {}", userId, e.message)
            0
        }
    }

    /**
     * Counts total active users in the system (excluding one specific user).
     *
     * Used when no location filter is specified in search queries.
     * Provides a baseline for threshold calculations in global/remote matching.
     *
     * @param excludeUserId User ID to exclude from count (typically the searching user)
     * @return Count of all other active users, or 0 if error occurs
     */
    suspend fun countTotalActiveUsers(excludeUserId: String): Int {
        return try {
            val query = """
                SELECT COUNT(DISTINCT u.id) as user_count
                FROM user_registration_data u
                INNER JOIN user_profiles up ON u.id = up.user_id
                WHERE u.id != ?
            """.trimIndent()

            TransactionManager.current().connection.prepareStatement(query, false)
                .also { statement ->
                    statement[1] = excludeUserId
                    val rs = statement.executeQuery()
                    if (rs.next()) {
                        return rs.getInt("user_count")
                    }
                }
            0
        } catch (e: Exception) {
            log.error("Error counting total active users (excluding {}): {}", excludeUserId, e.message)
            0
        }
    }

    /**
     * Counts users by their online status within a time window.
     *
     * Useful for prioritizing active matches and estimating real-time engagement.
     *
     * @param minutesAgo Time window for "recently online" status (default 30 minutes)
     * @return Count of users seen online within the window
     */
    suspend fun countRecentlyOnlineUsers(minutesAgo: Int = 30): Int {
        return try {
            val query = """
                SELECT COUNT(DISTINCT user_id) as user_count
                FROM user_presence
                WHERE last_seen >= NOW() - INTERVAL '$minutesAgo minutes'
                    AND status IN ('online', 'away')
            """.trimIndent()

            TransactionManager.current().connection.prepareStatement(query, false)
                .also { statement ->
                    val rs = statement.executeQuery()
                    if (rs.next()) {
                        return rs.getInt("user_count")
                    }
                }
            0
        } catch (e: Exception) {
            log.error("Error counting recently online users: {}", e.message)
            0
        }
    }

    /**
     * Fetches active posting IDs for a specific user.
     *
     * Used to identify what a user is currently offering,
     * which helps in complementary matching.
     *
     * @param userId User ID to query
     * @return List of posting IDs that are currently active (not expired)
     */
    suspend fun fetchActivePostingIds(userId: String): List<String> {
        return try {
            val query = """
                SELECT p.id
                FROM user_postings p
                WHERE p.user_id = ?
                    AND p.status = 'active'
                    AND (p.expires_at IS NULL OR p.expires_at > NOW())
            """.trimIndent()

            val postingIds = mutableListOf<String>()
            TransactionManager.current().connection.prepareStatement(query, false)
                .also { statement ->
                    statement[1] = userId
                    val rs = statement.executeQuery()
                    while (rs.next()) {
                        postingIds.add(rs.getString("id"))
                    }
                }
            postingIds
        } catch (e: Exception) {
            log.error("Error fetching active posting IDs for user {}: {}", userId, e.message)
            emptyList()
        }
    }

    /**
     * Fetches active posting IDs for multiple users in a batch query.
     *
     * More efficient than individual queries when processing multiple users.
     *
     * @param userIds List of user IDs to query
     * @return Map of userId → list of their active posting IDs
     */
    suspend fun fetchActivePostingIdsForUsers(userIds: List<String>): Map<String, List<String>> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }

        return try {
            // Build parameterized query with placeholders
            val placeholders = userIds.joinToString(",") { "?" }
            val query = """
                SELECT p.user_id, p.id
                FROM user_postings p
                WHERE p.user_id IN ($placeholders)
                    AND p.status = 'active'
                    AND (p.expires_at IS NULL OR p.expires_at > NOW())
                ORDER BY p.user_id, p.created_at DESC
            """.trimIndent()

            val result = mutableMapOf<String, MutableList<String>>()
            TransactionManager.current().connection.prepareStatement(query, false)
                .also { statement ->
                    userIds.forEachIndexed { index, userId ->
                        statement[index + 1] = userId
                    }
                    val rs = statement.executeQuery()
                    while (rs.next()) {
                        val uid = rs.getString("user_id")
                        val postingId = rs.getString("id")
                        result.getOrPut(uid) { mutableListOf() }.add(postingId)
                    }
                }
            result
        } catch (e: Exception) {
            log.error("Error fetching active posting IDs for {} users: {}", userIds.size, e.message)
            emptyMap()
        }
    }

    /**
     * Gets basic statistics about user activity in the system.
     *
     * @return UserActivityStats containing various user counts
     */
    suspend fun getUserActivityStats(): UserActivityStats {
        return try {
            val query = """
                SELECT
                    COUNT(DISTINCT u.id) as total_users,
                    COUNT(DISTINCT CASE WHEN p.last_seen >= NOW() - INTERVAL '24 hours' 
                                   THEN u.id END) as active_24h,
                    COUNT(DISTINCT CASE WHEN p.last_seen >= NOW() - INTERVAL '7 days' 
                                   THEN u.id END) as active_7d,
                    COUNT(DISTINCT CASE WHEN up.location IS NOT NULL 
                                   THEN u.id END) as with_location
                FROM user_registration_data u
                LEFT JOIN user_profiles up ON u.id = up.user_id
                LEFT JOIN user_presence p ON u.id = p.user_id
            """.trimIndent()

            TransactionManager.current().connection.prepareStatement(query, false)
                .also { statement ->
                    val rs = statement.executeQuery()
                    if (rs.next()) {
                        return UserActivityStats(
                            totalUsers = rs.getInt("total_users"),
                            activeLast24h = rs.getInt("active_24h"),
                            activeLast7d = rs.getInt("active_7d"),
                            usersWithLocation = rs.getInt("with_location")
                        )
                    }
                }
            UserActivityStats(0, 0, 0, 0)
        } catch (e: Exception) {
            log.error("Error getting user activity stats: {}", e.message)
            UserActivityStats(0, 0, 0, 0)
        }
    }

    /**
     * Data class holding user activity statistics.
     */
    data class UserActivityStats(
        val totalUsers: Int,
        val activeLast24h: Int,
        val activeLast7d: Int,
        val usersWithLocation: Int
    ) {
        val active24hPercentage: Double
            get() = if (totalUsers > 0) (activeLast24h * 100.0 / totalUsers) else 0.0

        val locationCoveragePercentage: Double
            get() = if (totalUsers > 0) (usersWithLocation * 100.0 / totalUsers) else 0.0
    }
}
