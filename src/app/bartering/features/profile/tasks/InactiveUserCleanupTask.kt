package app.bartering.features.profile.tasks

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.profile.cache.UserActivityCache
import app.bartering.features.profile.db.UserPresenceTable
import app.bartering.features.profile.db.UserRegistrationDataTable
import app.bartering.features.notifications.service.NotificationOrchestrator
import app.bartering.features.notifications.model.NotificationData
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Background task to identify and handle inactive users.
 * 
 * Runs daily to:
 * 1. Identify users inactive for 60 days - send reactivation email
 * 2. Identify users inactive for 120 days - send final warning
 * 3. Optionally auto-delete users inactive for 180+ days
 * 
 * Configuration:
 * - INACTIVE_THRESHOLD_DAYS: 30 days (hide from nearby searches)
 * - DORMANT_THRESHOLD_DAYS: 90 days (hide from all searches)
 * - REACTIVATION_EMAIL_DAYS: 60 days (send "we miss you" email)
 * - WARNING_EMAIL_DAYS: 120 days (send "account will be deleted" warning)
 * - AUTO_DELETE_DAYS: 180 days (optional auto-deletion, disabled by default)
 */
class InactiveUserCleanupTask(
    private val notificationOrchestrator: NotificationOrchestrator? = null,
    private val enableAutoDelete: Boolean = false,
    private val autoDeleteThresholdDays: Long = 180
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "InactiveUserCleanup").apply { isDaemon = true }
    }
    
    companion object {
        private const val INACTIVE_THRESHOLD_DAYS = 30L    // Hide from nearby searches
        private const val DORMANT_THRESHOLD_DAYS = 90L     // Hide from all searches
        private const val REACTIVATION_EMAIL_DAYS = 60L    // Send reactivation email
        private const val WARNING_EMAIL_DAYS = 120L        // Send deletion warning
    }
    
    /**
     * Start the cleanup task.
     * Runs daily at 3 AM.
     */
    fun start() {
        log.info("Starting InactiveUserCleanupTask (auto-delete: {})", enableAutoDelete)
        
        // Calculate initial delay to run at 3 AM
        val initialDelayHours = calculateInitialDelay()
        
        scheduler.scheduleAtFixedRate({
            try {
                processInactiveUsers()
            } catch (e: Exception) {
                log.error("Error during inactive user cleanup", e)
            }
        }, initialDelayHours, 24, TimeUnit.HOURS)
    }
    
    /**
     * Stop the cleanup task.
     */
    fun stop() {
        log.info("Stopping InactiveUserCleanupTask")
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
    }
    
    /**
     * Main processing logic for inactive users.
     */
    private fun processInactiveUsers() {
        log.info("Starting inactive user processing")
        
        var reactivationEmailsSent = 0
        var warningEmailsSent = 0
        var usersDeleted = 0
        
        try {
            val now = Instant.now()
            
            // Get all users with their last activity
            val userActivities = runBlocking {
                dbQuery {
                    UserPresenceTable.selectAll().map { row ->
                        row[UserPresenceTable.userId] to row[UserPresenceTable.lastActivityAt]
                    }
                }
            }
            
            userActivities.forEach { (userId, lastActivityAt) ->
                val daysSinceActive = ChronoUnit.DAYS.between(lastActivityAt, now)
                
                when {
                    // Send reactivation email at 60 days
                    daysSinceActive == REACTIVATION_EMAIL_DAYS -> {
                        sendReactivationEmail(userId)
                        reactivationEmailsSent++
                    }
                    
                    // Send warning email at 120 days
                    daysSinceActive == WARNING_EMAIL_DAYS -> {
                        sendDeletionWarning(userId)
                        warningEmailsSent++
                    }
                    
                    // Auto-delete at threshold (if enabled)
                    enableAutoDelete && daysSinceActive >= autoDeleteThresholdDays -> {
                        autoDeleteUser(userId)
                        usersDeleted++
                    }
                }
            }
            
            log.info(
                "Inactive user processing complete: {} reactivation emails, {} warnings, {} auto-deleted",
                reactivationEmailsSent,
                warningEmailsSent,
                usersDeleted
            )
            
        } catch (e: Exception) {
            log.error("Error processing inactive users", e)
        }
    }
    
    /**
     * Send reactivation email to user inactive for 60 days.
     */
    private fun sendReactivationEmail(userId: String) {
        notificationOrchestrator?.let { orchestrator ->
            try {
                log.info("Sending reactivation email to userId={}", userId)
                
                runBlocking {
                    orchestrator.sendNotification(
                        userId = userId,
                        notification = NotificationData(
                            title = "We miss you!",
                            body = "It's been a while since we've seen you. Check out what's new in your area!",
                            actionUrl = "/profile",
                            data = mapOf(
                                "type" to "reactivation",
                                "days_inactive" to REACTIVATION_EMAIL_DAYS.toString()
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                log.warn("Failed to send reactivation email to userId={}", userId, e)
            }
        }
    }
    
    /**
     * Send deletion warning to user inactive for 120 days.
     */
    private fun sendDeletionWarning(userId: String) {
        notificationOrchestrator?.let { orchestrator ->
            try {
                log.info("Sending deletion warning to userId={}", userId)
                
                val daysUntilDeletion = autoDeleteThresholdDays - WARNING_EMAIL_DAYS
                
                runBlocking {
                    orchestrator.sendNotification(
                        userId = userId,
                        notification = NotificationData(
                            title = "Account Inactivity Notice",
                            body = if (enableAutoDelete) {
                                "Your account will be automatically deleted in $daysUntilDeletion days due to inactivity. Log in to keep your account active."
                            } else {
                                "Your account has been inactive for $WARNING_EMAIL_DAYS days. Your profile is currently hidden from searches."
                            },
                            actionUrl = "/login",
                            data = mapOf(
                                "type" to "deletion_warning",
                                "days_inactive" to WARNING_EMAIL_DAYS.toString(),
                                "auto_delete_enabled" to enableAutoDelete.toString()
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                log.warn("Failed to send deletion warning to userId={}", userId, e)
            }
        }
    }
    
    /**
     * Auto-delete user account after threshold.
     * Only runs if auto-delete is enabled.
     */
    private fun autoDeleteUser(userId: String) {
        try {
            log.warn("Auto-deleting inactive user: userId={}", userId)
            
            // Remove from activity cache first
            UserActivityCache.removeUser(userId)
            
            // Delete user account (cascades to all related data)
            val deleted = runBlocking {
                dbQuery {
                    UserRegistrationDataTable.deleteWhere { 
                        UserRegistrationDataTable.id eq userId 
                    } > 0
                }
            }
            
            if (deleted) {
                log.info("Successfully auto-deleted userId={}", userId)
            } else {
                log.warn("Failed to auto-delete userId={} (may already be deleted)", userId)
            }
            
        } catch (e: Exception) {
            log.error("Error auto-deleting userId={}", userId, e)
        }
    }
    
    /**
     * Calculate hours until 3 AM for initial delay.
     */
    private fun calculateInitialDelay(): Long {
        val now = java.time.LocalTime.now()
        val target = java.time.LocalTime.of(3, 0)
        
        val hoursUntil = if (now.isBefore(target)) {
            java.time.Duration.between(now, target).toHours()
        } else {
            24 - java.time.Duration.between(target, now).toHours()
        }
        
        return hoursUntil
    }
    
    /**
     * Manually trigger processing (for testing).
     */
    fun processNow() {
        processInactiveUsers()
    }
}
