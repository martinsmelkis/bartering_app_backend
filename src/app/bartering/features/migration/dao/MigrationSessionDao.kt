package app.bartering.features.migration.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.migration.db.MigrationSessionsTable
import app.bartering.features.migration.model.MigrationConstraints
import app.bartering.features.migration.model.MigrationSession
import app.bartering.features.migration.model.MigrationSessionStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

class MigrationSessionDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Create a new migration session.
     * Returns the session code (10-character string) or null if failed.
     */
    suspend fun createSession(
        userId: String,
        sourceDeviceId: String,
        sourceDeviceKeyId: String?,
        sourcePublicKey: String?
    ): String? = dbQuery {
        try {
            // Check if user has too many active sessions
            val activeCount = MigrationSessionsTable
                .select(MigrationSessionsTable.id)
                .where { 
                    (MigrationSessionsTable.userId eq userId) and
                    (MigrationSessionsTable.status inList listOf("pending", "awaiting_confirmation", "transferring")) and
                    (MigrationSessionsTable.expiresAt greater Instant.now())
                }
                .count()

            if (activeCount >= MigrationConstraints.MAX_ACTIVE_SESSIONS_PER_USER) {
                log.warn("User {} has too many active migration sessions", userId)
                return@dbQuery null
            }

            // Generate unique 10-character session code
            var sessionCode: String
            var attempts = 0
            do {
                sessionCode = generateSessionCode()
                val exists = MigrationSessionsTable
                    .select(MigrationSessionsTable.id)
                    .where { 
                        (MigrationSessionsTable.sessionCode eq sessionCode) and
                        (MigrationSessionsTable.expiresAt greater Instant.now())
                    }
                    .count() > 0
                attempts++
            } while (exists && attempts < 10)

            if (attempts >= 10) {
                log.error("Failed to generate unique session code after 10 attempts")
                return@dbQuery null
            }

            val sessionId = UUID.randomUUID().toString()
            val now = Instant.now()
            val expiresAt = now.plusSeconds((MigrationConstraints.SESSION_EXPIRY_MINUTES * 60).toLong())

            MigrationSessionsTable.insert {
                it[id] = sessionId
                it[MigrationSessionsTable.sessionCode] = sessionCode
                it[MigrationSessionsTable.userId] = userId
                it[MigrationSessionsTable.sourceDeviceId] = sourceDeviceId
                it[MigrationSessionsTable.sourceDeviceKeyId] = sourceDeviceKeyId
                it[MigrationSessionsTable.sourcePublicKey] = sourcePublicKey
                it[MigrationSessionsTable.targetDeviceId] = null
                it[MigrationSessionsTable.targetDeviceKeyId] = null
                it[MigrationSessionsTable.targetPublicKey] = null
                it[MigrationSessionsTable.status] = MigrationSessionStatus.PENDING.name.lowercase()
                it[MigrationSessionsTable.encryptedPayload] = null
                it[MigrationSessionsTable.payloadCreatedAt] = null
                it[MigrationSessionsTable.createdAt] = now
                it[MigrationSessionsTable.expiresAt] = expiresAt
                it[MigrationSessionsTable.completedAt] = null
                it[MigrationSessionsTable.attemptCount] = 0
            }

            log.info("Created migration session {} for user {}, source device {}", 
                sessionCode, userId, sourceDeviceId)
            
            sessionCode
        } catch (e: Exception) {
            log.error("Failed to create migration session for user {}", userId, e)
            null
        }
    }

    /**
     * Get a session by its session code (the 10-character code users enter).
     */
    suspend fun getSessionByCode(sessionCode: String): MigrationSession? = dbQuery {
        MigrationSessionsTable
            .selectAll()
            .where { MigrationSessionsTable.sessionCode eq sessionCode }
            .firstOrNull()
            ?.let { rowToSession(it) }
    }

    /**
     * Get a session by its internal UUID.
     */
    suspend fun getSessionById(sessionId: String): MigrationSession? = dbQuery {
        MigrationSessionsTable
            .selectAll()
            .where { MigrationSessionsTable.id eq sessionId }
            .firstOrNull()
            ?.let { rowToSession(it) }
    }

    /**
     * Register a target device for a session.
     * Called when the target device enters the migration code.
     */
    suspend fun registerTargetDevice(
        sessionCode: String,
        targetDeviceId: String,
        targetPublicKey: String
    ): MigrationSession? = dbQuery {
        try {
            val session = getSessionByCode(sessionCode) ?: return@dbQuery null

            // Check if session is still valid
            if (!session.isActive) {
                log.warn("Attempt to register target for inactive session {}", sessionCode)
                return@dbQuery null
            }

            // Update the session with target device info
            val updated = MigrationSessionsTable.update({
                (MigrationSessionsTable.id eq session.id) and
                (MigrationSessionsTable.status eq MigrationSessionStatus.PENDING.name.lowercase())
            }) {
                it[MigrationSessionsTable.targetDeviceId] = targetDeviceId
                it[MigrationSessionsTable.targetPublicKey] = targetPublicKey
                it[MigrationSessionsTable.status] = MigrationSessionStatus.AWAITING_CONFIRMATION.name.lowercase()
            }

            if (updated > 0) {
                log.info("Registered target device {} for session {}", targetDeviceId, sessionCode)
                getSessionById(session.id)
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("Failed to register target device for session {}", sessionCode, e)
            null
        }
    }

    /**
     * Store the encrypted migration payload.
     * Called by the source device after user confirms.
     */
    suspend fun storePayload(
        sessionId: String,
        encryptedPayload: String
    ): Boolean = dbQuery {
        try {
            val now = Instant.now()
            
            val updated = MigrationSessionsTable.update({
                (MigrationSessionsTable.id eq sessionId) and
                (MigrationSessionsTable.status eq MigrationSessionStatus.AWAITING_CONFIRMATION.name.lowercase())
            }) {
                it[MigrationSessionsTable.encryptedPayload] = encryptedPayload
                it[MigrationSessionsTable.payloadCreatedAt] = now
                it[MigrationSessionsTable.status] = MigrationSessionStatus.TRANSFERRING.name.lowercase()
            }

            if (updated > 0) {
                log.info("Stored migration payload for session {}", sessionId)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.error("Failed to store payload for session {}", sessionId, e)
            false
        }
    }

    /**
     * Get the encrypted payload for a session.
     * Called by the target device to retrieve the data.
     */
    suspend fun getPayload(sessionId: String): String? = dbQuery {
        MigrationSessionsTable
            .select(MigrationSessionsTable.encryptedPayload)
            .where { MigrationSessionsTable.id eq sessionId }
            .firstOrNull()
            ?.get(MigrationSessionsTable.encryptedPayload)
    }

    /**
     * Mark a session as completed.
     * Called after successful data transfer.
     */
    suspend fun completeSession(
        sessionId: String,
        targetDeviceKeyId: String? = null
    ): Boolean = dbQuery {
        try {
            val now = Instant.now()
            
            val updated = MigrationSessionsTable.update({
                MigrationSessionsTable.id eq sessionId
            }) {
                it[MigrationSessionsTable.status] = MigrationSessionStatus.COMPLETED.name.lowercase()
                it[MigrationSessionsTable.completedAt] = now
                if (targetDeviceKeyId != null) {
                    it[MigrationSessionsTable.targetDeviceKeyId] = targetDeviceKeyId
                }
            }

            if (updated > 0) {
                log.info("Completed migration session {}", sessionId)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.error("Failed to complete session {}", sessionId, e)
            false
        }
    }

    /**
     * Cancel a session.
     */
    suspend fun cancelSession(sessionId: String): Boolean = dbQuery {
        try {
            val updated = MigrationSessionsTable.update({
                (MigrationSessionsTable.id eq sessionId) and
                (MigrationSessionsTable.status inList listOf(
                    MigrationSessionStatus.PENDING.name.lowercase(),
                    MigrationSessionStatus.AWAITING_CONFIRMATION.name.lowercase()
                ))
            }) {
                it[MigrationSessionsTable.status] = MigrationSessionStatus.CANCELLED.name.lowercase()
            }

            updated > 0
        } catch (e: Exception) {
            log.error("Failed to cancel session {}", sessionId, e)
            false
        }
    }

    /**
     * Increment the attempt count (for rate limiting).
     */
    suspend fun incrementAttemptCount(sessionId: String): Boolean = dbQuery {
        try {
            MigrationSessionsTable.update({ MigrationSessionsTable.id eq sessionId }) {
                it[MigrationSessionsTable.attemptCount] = MigrationSessionsTable.attemptCount + 1
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clean up expired sessions.
     */
    suspend fun cleanupExpiredSessions(): Int = dbQuery {
        try {
            val now = Instant.now()
            
            // Mark expired sessions
            MigrationSessionsTable.update({
                (MigrationSessionsTable.expiresAt less now) and
                (MigrationSessionsTable.status inList listOf(
                    MigrationSessionStatus.PENDING.name.lowercase(),
                    MigrationSessionStatus.AWAITING_CONFIRMATION.name.lowercase(),
                    MigrationSessionStatus.TRANSFERRING.name.lowercase()
                ))
            }) {
                it[MigrationSessionsTable.status] = MigrationSessionStatus.EXPIRED.name.lowercase()
            }

            // Delete old completed/expired/cancelled sessions (older than 7 days)
            val sevenDaysAgo = now.minusSeconds(7 * 24 * 60 * 60)
            val deleted = MigrationSessionsTable.deleteWhere {
                (MigrationSessionsTable.createdAt less sevenDaysAgo) and
                (MigrationSessionsTable.status inList listOf(
                    MigrationSessionStatus.COMPLETED.name.lowercase(),
                    MigrationSessionStatus.EXPIRED.name.lowercase(),
                    MigrationSessionStatus.CANCELLED.name.lowercase()
                ))
            }

            deleted
        } catch (e: Exception) {
            log.error("Failed to cleanup expired sessions", e)
            0
        }
    }

    /**
     * Get all active sessions for a user.
     */
    suspend fun getActiveSessionsForUser(userId: String): List<MigrationSession> = dbQuery {
        MigrationSessionsTable
            .selectAll()
            .where {
                (MigrationSessionsTable.userId eq userId) and
                (MigrationSessionsTable.expiresAt greater Instant.now()) and
                (MigrationSessionsTable.status inList listOf(
                    MigrationSessionStatus.PENDING.name.lowercase(),
                    MigrationSessionStatus.AWAITING_CONFIRMATION.name.lowercase(),
                    MigrationSessionStatus.TRANSFERRING.name.lowercase()
                ))
            }
            .orderBy(MigrationSessionsTable.createdAt to SortOrder.DESC)
            .map { rowToSession(it) }
    }

    /**
     * BACKWARD COMPATIBILITY: Get recent completed sessions for a user.
     * Used to authenticate newly migrated devices that haven't been registered yet.
     */
    suspend fun getRecentCompletedSessions(userId: String, sinceMinutes: Long = 60): List<MigrationSession> = dbQuery {
        val since = Instant.now().minusSeconds(sinceMinutes * 60)
        
        MigrationSessionsTable
            .selectAll()
            .where {
                (MigrationSessionsTable.userId eq userId) and
                (MigrationSessionsTable.status eq MigrationSessionStatus.COMPLETED.name.lowercase()) and
                (MigrationSessionsTable.completedAt greater since)
            }
            .orderBy(MigrationSessionsTable.completedAt to SortOrder.DESC)
            .map { rowToSession(it) }
    }

    /**
     * Delete all migration sessions for a user.
     * Called when a user is deleted to clean up migration data.
     */
    suspend fun deleteAllSessionsForUser(userId: String): Int = dbQuery {
        try {
            val deleted = MigrationSessionsTable.deleteWhere {
                MigrationSessionsTable.userId eq userId
            }
            log.info("Deleted {} migration sessions for user {}", deleted, userId)
            deleted
        } catch (e: Exception) {
            log.error("Failed to delete migration sessions for user {}", userId, e)
            0
        }
    }

    // ============================================================================
    // PRIVATE HELPERS
    // ============================================================================

    private fun rowToSession(row: ResultRow): MigrationSession {
        return MigrationSession(
            id = row[MigrationSessionsTable.id],
            sessionCode = row[MigrationSessionsTable.sessionCode],
            userId = row[MigrationSessionsTable.userId],
            sourceDeviceId = row[MigrationSessionsTable.sourceDeviceId],
            sourceDeviceKeyId = row[MigrationSessionsTable.sourceDeviceKeyId],
            sourcePublicKey = row[MigrationSessionsTable.sourcePublicKey],
            targetDeviceId = row[MigrationSessionsTable.targetDeviceId],
            targetDeviceKeyId = row[MigrationSessionsTable.targetDeviceKeyId],
            targetPublicKey = row[MigrationSessionsTable.targetPublicKey],
            status = MigrationSessionStatus.valueOf(row[MigrationSessionsTable.status].uppercase()),
            encryptedPayload = row[MigrationSessionsTable.encryptedPayload],
            payloadCreatedAt = row[MigrationSessionsTable.payloadCreatedAt]?.let { java.time.Instant.from(it) },
            createdAt = java.time.Instant.from(row[MigrationSessionsTable.createdAt]),
            expiresAt = java.time.Instant.from(row[MigrationSessionsTable.expiresAt]),
            completedAt = row[MigrationSessionsTable.completedAt]?.let { java.time.Instant.from(it) },
            attemptCount = row[MigrationSessionsTable.attemptCount]
        )
    }

    private fun generateSessionCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..MigrationConstraints.SESSION_CODE_LENGTH)
            .map { chars.random() }
            .joinToString("")
    }

    // ============================================================================
    // BACKWARD COMPATIBILITY METHODS
    // ============================================================================

    /**
     * BACKWARD COMPATIBILITY: Create a session on-demand when target device tries to join.
     * This supports old clients that generate sessions locally without calling initiate.
     * The session starts with only target info; source info is filled in when payload arrives.
     */
    suspend fun createSessionOnDemand(
        sessionCode: String,
        targetDeviceId: String,
        targetPublicKey: String
    ): Boolean = dbQuery {
        try {
            // Check if session already exists
            val existing = MigrationSessionsTable
                .select(MigrationSessionsTable.id)
                .where { MigrationSessionsTable.sessionCode eq sessionCode }
                .firstOrNull()
            
            if (existing != null) {
                return@dbQuery true // Session already exists
            }

            val sessionId = UUID.randomUUID().toString()
            val now = Instant.now()
            val expiresAt = now.plusSeconds((MigrationConstraints.SESSION_EXPIRY_MINUTES * 60).toLong())

            MigrationSessionsTable.insert {
                it[id] = sessionId
                it[MigrationSessionsTable.sessionCode] = sessionCode
                it[MigrationSessionsTable.userId] = null // Will be filled when source sends payload
                it[MigrationSessionsTable.sourceDeviceId] = null // Will be filled when source sends payload
                it[MigrationSessionsTable.sourceDeviceKeyId] = null
                it[MigrationSessionsTable.sourcePublicKey] = null
                it[MigrationSessionsTable.targetDeviceId] = targetDeviceId
                it[MigrationSessionsTable.targetDeviceKeyId] = null
                it[MigrationSessionsTable.targetPublicKey] = targetPublicKey
                it[MigrationSessionsTable.status] = MigrationSessionStatus.PENDING.name.lowercase()
                it[MigrationSessionsTable.encryptedPayload] = null
                it[MigrationSessionsTable.payloadCreatedAt] = null
                it[MigrationSessionsTable.createdAt] = now
                it[MigrationSessionsTable.expiresAt] = expiresAt
                it[MigrationSessionsTable.completedAt] = null
                it[MigrationSessionsTable.attemptCount] = 0
            }

            log.info("Created on-demand migration session {} for target device {}", sessionCode, targetDeviceId)
            true
        } catch (e: Exception) {
            log.error("Failed to create on-demand session {}", sessionCode, e)
            false
        }
    }

    /**
     * BACKWARD COMPATIBILITY: Update session with source info when payload arrives.
     * This is used when the session was created on-demand by the target device.
     */
    suspend fun updateSessionWithSourceInfo(
        sessionId: String,
        userId: String,
        sourceDeviceId: String,
        sourcePublicKey: String?
    ): Boolean = dbQuery {
        try {
            // Update session - check for both NULL and "PENDING" userIds
            val updated = MigrationSessionsTable.update({
                (MigrationSessionsTable.id eq sessionId) and
                (MigrationSessionsTable.userId.isNull() or 
                 (MigrationSessionsTable.userId eq "PENDING")) // Handle both cases
            }) {
                it[MigrationSessionsTable.userId] = userId
                it[MigrationSessionsTable.sourceDeviceId] = sourceDeviceId
                it[MigrationSessionsTable.sourcePublicKey] = sourcePublicKey
                it[MigrationSessionsTable.status] = MigrationSessionStatus.AWAITING_CONFIRMATION.name.lowercase()
            }
            
            // If no rows updated, try without the userId check (session might already have userId set)
            if (updated == 0) {
                val updated2 = MigrationSessionsTable.update({
                    MigrationSessionsTable.id eq sessionId
                }) {
                    it[MigrationSessionsTable.sourceDeviceId] = sourceDeviceId
                    it[MigrationSessionsTable.sourcePublicKey] = sourcePublicKey
                }
                return@dbQuery updated2 > 0
            }
            
            updated > 0
        } catch (e: Exception) {
            log.error("Failed to update session {} with source info", sessionId, e)
            false
        }
    }
}
