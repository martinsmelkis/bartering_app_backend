package app.bartering.features.migration.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.migration.db.MigrationAuditLogTable
import app.bartering.features.migration.db.MigrationSessionsTable
import app.bartering.features.migration.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * Unified DAO for migration sessions (device-to-device and email recovery).
 */
class MigrationDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    // ============================================================================
    // EMAIL RECOVERY
    // ============================================================================

    suspend fun createEmailRecoverySession(
        userId: String,
        email: String,
        ipAddress: String?
    ): Pair<String, String>? = dbQuery {
        try {
            // Cancel any existing email_recovery sessions for this user
            // (User can only have one active email recovery at a time)
            val cancelled = MigrationSessionsTable.update({
                (MigrationSessionsTable.userId eq userId) and
                (MigrationSessionsTable.type eq "email_recovery") and
                (MigrationSessionsTable.status inList listOf("pending", "verified"))
            }) {
                it[MigrationSessionsTable.status] = "cancelled"
            }
            if (cancelled > 0) {
                log.info("Cancelled {} existing email recovery session(s) for user {}", cancelled, userId)
            }

            // Check active session limit
            val activeCount = MigrationSessionsTable
                .select(MigrationSessionsTable.id)
                .where {
                    (MigrationSessionsTable.userId eq userId) and
                    (MigrationSessionsTable.status inList listOf("pending", "verified")) and
                    (MigrationSessionsTable.expiresAt greater Instant.now())
                }
                .count()

            if (activeCount >= MigrationConstraints.MAX_ACTIVE_SESSIONS) {
                log.warn("User {} has too many active sessions", userId)
                return@dbQuery null
            }

            val sessionId = UUID.randomUUID().toString()
            
            // Generate raw code (no formatting) and hash it
            val rawCode = generateRawRecoveryCode()
            val hashedCode = BCrypt.hashpw(rawCode, BCrypt.gensalt(12))
            
            // Format code with dashes for display/email: "ABC-DEF"
            val formattedCode = rawCode.chunked(3).joinToString("-")
            
            val now = Instant.now()
            val expiresAt = now.plusSeconds(MigrationConstraints.RECOVERY_EXPIRY_HOURS * 60L * 60L)

            MigrationSessionsTable.insert {
                it[MigrationSessionsTable.id] = sessionId
                it[MigrationSessionsTable.userId] = userId
                it[MigrationSessionsTable.type] = "email_recovery"
                it[MigrationSessionsTable.recoveryCodeHash] = hashedCode
                it[MigrationSessionsTable.contactEmail] = email
                it[MigrationSessionsTable.ipAddress] = ipAddress
                it[MigrationSessionsTable.status] = "pending"
                it[MigrationSessionsTable.createdAt] = now
                it[MigrationSessionsTable.expiresAt] = expiresAt
                it[MigrationSessionsTable.attemptCount] = 0
                it[MigrationSessionsTable.maxAttempts] = MigrationConstraints.MAX_ATTEMPTS
            }

            logAudit(
                eventType = MigrationEventType.INITIATED,
                migrationType = "email_recovery",
                userId = userId,
                sessionId = sessionId,
                ipAddress = ipAddress,
                riskScore = 25
            )

            Pair(sessionId, formattedCode)
        } catch (e: Exception) {
            log.error("Failed to create email recovery session for user {}", userId, e)
            null
        }
    }

    suspend fun verifyEmailRecoveryCode(
        sessionId: String,
        plaintextCode: String,
        newDeviceId: String,
        newDevicePublicKey: String
    ): Boolean = dbQuery {
        val session = getSession(sessionId) ?: return@dbQuery false

        if (session.type != "email_recovery") return@dbQuery false
        if (session.status != "pending" && session.status != "verified") return@dbQuery false

        if (session.isExpired) {
            updateStatus(sessionId, "expired")
            return@dbQuery false
        }

        if (session.attemptCount >= session.maxAttempts) {
            updateStatus(sessionId, "failed")
            return@dbQuery false
        }

        // Increment attempts
        MigrationSessionsTable.update({ MigrationSessionsTable.id eq sessionId }) {
            it[attemptCount] = attemptCount + 1
        }

        // Get stored hash
        val hashedCode = MigrationSessionsTable
            .select(MigrationSessionsTable.recoveryCodeHash)
            .where { MigrationSessionsTable.id eq sessionId }
            .firstOrNull()
            ?.get(MigrationSessionsTable.recoveryCodeHash)
            ?: return@dbQuery false

        // Verify code (normalize input: remove dashes/spaces, uppercase)
        val normalizedCode = plaintextCode.uppercase().replace(Regex("[^A-Z0-9]"), "")
        
        if (!BCrypt.checkpw(normalizedCode, hashedCode)) {
            return@dbQuery false
        }

        // Success
        val now = Instant.now()
        MigrationSessionsTable.update({ MigrationSessionsTable.id eq sessionId }) {
            it[status] = "verified"
            it[verifiedAt] = now
            it[MigrationSessionsTable.newDeviceId] = newDeviceId
            it[MigrationSessionsTable.newDevicePublicKey] = newDevicePublicKey
        }

        logAudit(
            eventType = MigrationEventType.CODE_VERIFIED,
            migrationType = "email_recovery",
            userId = session.userId,
            sessionId = sessionId,
            riskScore = 10
        )

        true
    }

    // ============================================================================
    // DEVICE-TO-DEVICE MIGRATION
    // ============================================================================

    suspend fun createDeviceMigrationSession(
        userId: String,
        sourceDeviceId: String,
        sourcePublicKey: String,
        ipAddress: String?
    ): String? = dbQuery {
        try {
            // Check active session limit
            val activeCount = MigrationSessionsTable
                .select(MigrationSessionsTable.id)
                .where {
                    (MigrationSessionsTable.userId eq userId) and
                    (MigrationSessionsTable.status inList listOf("pending", "awaiting_confirmation")) and
                    (MigrationSessionsTable.expiresAt greater Instant.now())
                }
                .count()

            if (activeCount >= 3) { // Max 3 active device migration sessions
                log.warn("User {} has too many active device migration sessions", userId)
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
                log.error("Failed to generate unique session code")
                return@dbQuery null
            }

            val sessionId = UUID.randomUUID().toString()
            val now = Instant.now()
            val expiresAt = now.plusSeconds(15 * 60) // 15 minutes

            MigrationSessionsTable.insert {
                it[MigrationSessionsTable.id] = sessionId
                it[MigrationSessionsTable.userId] = userId
                it[MigrationSessionsTable.type] = "device_to_device"
                it[MigrationSessionsTable.sessionCode] = sessionCode
                it[MigrationSessionsTable.sourceDeviceId] = sourceDeviceId
                it[MigrationSessionsTable.sourcePublicKey] = sourcePublicKey
                it[MigrationSessionsTable.status] = "pending"
                it[MigrationSessionsTable.createdAt] = now
                it[MigrationSessionsTable.expiresAt] = expiresAt
                it[MigrationSessionsTable.attemptCount] = 0
                it[MigrationSessionsTable.maxAttempts] = 5
            }

            logAudit(
                eventType = MigrationEventType.INITIATED,
                migrationType = "device_to_device",
                userId = userId,
                sessionId = sessionId,
                ipAddress = ipAddress,
                riskScore = 10
            )

            sessionCode
        } catch (e: Exception) {
            log.error("Failed to create device migration session for user {}", userId, e)
            null
        }
    }

    suspend fun registerMigrationTarget(
        sessionCode: String,
        targetDeviceId: String,
        targetPublicKey: String
    ): MigrationSession? = dbQuery {
        val session = getSessionByCode(sessionCode) ?: return@dbQuery null

        if (session.type != "device_to_device") return@dbQuery null
        if (!session.isActive) return@dbQuery null

        val updated = MigrationSessionsTable.update({
            (MigrationSessionsTable.id eq session.id) and
            (MigrationSessionsTable.status eq "pending")
        }) {
            it[MigrationSessionsTable.targetDeviceId] = targetDeviceId
            it[MigrationSessionsTable.targetPublicKey] = targetPublicKey
            it[status] = "awaiting_confirmation"
        }

        if (updated > 0) getSession(session.id) else null
    }

    suspend fun storeMigrationPayload(
        sessionId: String,
        encryptedPayload: String
    ): Boolean = dbQuery {
        val now = Instant.now()

        val updated = MigrationSessionsTable.update({
            (MigrationSessionsTable.id eq sessionId) and
            (MigrationSessionsTable.status eq "awaiting_confirmation")
        }) {
            it[MigrationSessionsTable.encryptedPayload] = encryptedPayload
            it[MigrationSessionsTable.payloadCreatedAt] = now
            it[status] = "transferring"
        }

        updated > 0
    }

    // ============================================================================
    // COMMON OPERATIONS
    // ============================================================================

    suspend fun getSession(sessionId: String): MigrationSession? = dbQuery {
        MigrationSessionsTable
            .selectAll()
            .where { MigrationSessionsTable.id eq sessionId }
            .firstOrNull()
            ?.let { rowToSession(it) }
    }

    suspend fun getSessionByCode(sessionCode: String): MigrationSession? = dbQuery {
        MigrationSessionsTable
            .selectAll()
            .where { MigrationSessionsTable.sessionCode eq sessionCode }
            .firstOrNull()
            ?.let { rowToSession(it) }
    }

    /**
     * Get recent completed sessions for a user (for backward compatibility - device auth after migration).
     */
    suspend fun getRecentCompletedSessions(userId: String, sinceMinutes: Long = 60): List<MigrationSession> = dbQuery {
        val since = Instant.now().minusSeconds(sinceMinutes * 60)
        
        MigrationSessionsTable
            .selectAll()
            .where {
                (MigrationSessionsTable.userId eq userId) and
                (MigrationSessionsTable.status eq "completed") and
                (MigrationSessionsTable.completedAt greater since)
            }
            .orderBy(MigrationSessionsTable.completedAt to SortOrder.DESC)
            .map { rowToSession(it) }
    }

    suspend fun completeSession(sessionId: String): Boolean = dbQuery {
        val now = Instant.now()

        val updated = MigrationSessionsTable.update({
            MigrationSessionsTable.id eq sessionId
        }) {
            it[status] = "completed"
            it[completedAt] = now
        }

        if (updated > 0) {
            val session = getSession(sessionId)
            if (session != null) {
                logAudit(
                    eventType = MigrationEventType.COMPLETED,
                    migrationType = session.type,
                    userId = session.userId,
                    sessionId = sessionId,
                    riskScore = 10
                )
            }
        }

        updated > 0
    }

    suspend fun cancelSession(sessionId: String): Boolean = dbQuery {
        MigrationSessionsTable.update({
            (MigrationSessionsTable.id eq sessionId) and
            (MigrationSessionsTable.status inList listOf("pending", "awaiting_confirmation", "verified"))
        }) {
            it[status] = "cancelled"
        } > 0
    }

    /**
     * Mark a session as failed (e.g., when email delivery fails).
     * This removes it from the active session count.
     */
    suspend fun failSession(sessionId: String, userId: String, errorMessage: String): Boolean = dbQuery {
        val updated = MigrationSessionsTable.update({
            MigrationSessionsTable.id eq sessionId
        }) {
            it[status] = "failed"
        }
        if (updated > 0) {
            logAudit(
                eventType = MigrationEventType.FAILED,
                migrationType = "email_recovery",
                userId = userId,
                sessionId = sessionId,
                details = mapOf("error" to errorMessage, "reason" to "email_send_failed")
            )
        }
        updated > 0
    }

    /**
     * Delete all migration sessions for a user (used when user is deleted).
     */
    suspend fun deleteAllSessionsForUser(userId: String): Int = dbQuery {
        val deleted = MigrationSessionsTable.deleteWhere {
            MigrationSessionsTable.userId eq userId
        }
        log.info("Deleted {} migration sessions for user {}", deleted, userId)
        deleted
    }

    suspend fun cleanupExpiredSessions(): Int = dbQuery {
        val now = Instant.now()

        // Mark expired
        val expired = MigrationSessionsTable.update({
            (MigrationSessionsTable.expiresAt less now) and
            (MigrationSessionsTable.status inList listOf("pending", "awaiting_confirmation", "transferring", "verified"))
        }) {
            it[status] = "expired"
        }

        // Delete old completed/expired/cancelled (30 days retention)
        val thirtyDaysAgo = now.minusSeconds(30 * 24 * 60 * 60)
        val deleted = MigrationSessionsTable.deleteWhere {
            (MigrationSessionsTable.createdAt less thirtyDaysAgo) and
            (MigrationSessionsTable.status inList listOf("completed", "expired", "cancelled", "failed"))
        }

        // Clean up old audit logs (90 days)
        val ninetyDaysAgo = now.minusSeconds(90 * 24 * 60 * 60)
        MigrationAuditLogTable.deleteWhere {
            MigrationAuditLogTable.createdAt less ninetyDaysAgo
        }

        expired + deleted
    }

    suspend fun isIPRateLimited(
        ipAddress: String,
        maxAttempts: Int = 5,
        windowMinutes: Int = 60
    ): Boolean = dbQuery {
        val since = Instant.now().minusSeconds(windowMinutes * 60L)

        val recentAttempts = MigrationAuditLogTable
            .selectAll()
            .where {
                (MigrationAuditLogTable.ipAddress eq ipAddress) and
                (MigrationAuditLogTable.eventType inList listOf("recovery_initiated", "recovery_failed", "migration_initiated")) and
                (MigrationAuditLogTable.createdAt greater since)
            }
            .count()

        recentAttempts >= maxAttempts
    }

    // ============================================================================
    // AUDIT LOGGING
    // ============================================================================

    suspend fun logAudit(
        eventType: String,
        migrationType: String,
        userId: String,
        sessionId: String? = null,
        ipAddress: String? = null,
        details: Map<String, String>? = null,
        riskScore: Int = 0
    ) = dbQuery {
        try {
            MigrationAuditLogTable.insert {
                it[MigrationAuditLogTable.eventType] = eventType
                it[MigrationAuditLogTable.migrationType] = migrationType
                it[MigrationAuditLogTable.userId] = userId
                it[MigrationAuditLogTable.sessionId] = sessionId
                it[MigrationAuditLogTable.ipAddress] = ipAddress
                it[MigrationAuditLogTable.details] = details
                it[MigrationAuditLogTable.riskScore] = riskScore
                it[createdAt] = Instant.now()
            }
        } catch (e: Exception) {
            log.error("Failed to log audit event: {}", eventType, e)
        }
    }

    // ============================================================================
    // PRIVATE HELPERS
    // ============================================================================

    private suspend fun updateStatus(sessionId: String, newStatus: String) = dbQuery {
        MigrationSessionsTable.update({ MigrationSessionsTable.id eq sessionId }) {
            it[status] = newStatus
        }
    }

    /**
     * Generate raw recovery code (without formatting)
     */
    private fun generateRawRecoveryCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..MigrationConstraints.RECOVERY_CODE_LENGTH)
            .map { chars.random() }
            .joinToString("")
    }

    private fun generateSessionCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..10)
            .map { chars.random() }
            .joinToString("")
    }

    private fun rowToSession(row: ResultRow): MigrationSession {
        return MigrationSession(
            id = row[MigrationSessionsTable.id],
            userId = row[MigrationSessionsTable.userId],
            type = row[MigrationSessionsTable.type],
            sessionCode = row[MigrationSessionsTable.sessionCode],
            status = row[MigrationSessionsTable.status],
            attemptCount = row[MigrationSessionsTable.attemptCount],
            maxAttempts = row[MigrationSessionsTable.maxAttempts],
            sourceDeviceId = row[MigrationSessionsTable.sourceDeviceId],
            targetDeviceId = row[MigrationSessionsTable.targetDeviceId] ?: row[MigrationSessionsTable.newDeviceId],
            targetPublicKey = row[MigrationSessionsTable.targetPublicKey] ?: row[MigrationSessionsTable.newDevicePublicKey],
            contactEmail = row[MigrationSessionsTable.contactEmail],
            encryptedPayload = row[MigrationSessionsTable.encryptedPayload],
            createdAt = row[MigrationSessionsTable.createdAt].toString(),
            expiresAt = row[MigrationSessionsTable.expiresAt].toString(),
            verifiedAt = row[MigrationSessionsTable.verifiedAt]?.toString(),
            completedAt = row[MigrationSessionsTable.completedAt]?.toString()
        )
    }
}

/**
 * Unified migration session data class.
 */
data class MigrationSession(
    val id: String,
    val userId: String,
    val type: String,              // 'device_to_device' or 'email_recovery'
    val sessionCode: String?,      // For device-to-device
    val status: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val sourceDeviceId: String?,
    val targetDeviceId: String?,   // Alias for both target and new_device
    val targetPublicKey: String?,   // For signature verification after migration
    val contactEmail: String?,
    val encryptedPayload: String?,  // Encrypted migration payload
    val createdAt: String,
    val expiresAt: String,
    val verifiedAt: String?,
    val completedAt: String?
) {
    val isExpired: Boolean
        get() = java.time.Instant.now().isAfter(java.time.Instant.parse(expiresAt))

    val isActive: Boolean
        get() = status in listOf("pending", "awaiting_confirmation", "transferring", "verified") && !isExpired

    val attemptsRemaining: Int
        get() = (maxAttempts - attemptCount).coerceAtLeast(0)
}
