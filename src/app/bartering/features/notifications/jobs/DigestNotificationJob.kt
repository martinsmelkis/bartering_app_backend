package app.bartering.features.notifications.jobs

import kotlinx.coroutines.*
import app.bartering.features.notifications.model.NotificationFrequency
import app.bartering.features.notifications.service.MatchNotificationService
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Background job for processing digest notifications (daily/weekly)
 * Runs on a schedule and sends batched notifications to users
 */
class DigestNotificationJob {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    private val matchService: MatchNotificationService by inject(MatchNotificationService::class.java)
    private var dailyJob: Job? = null
    private var weeklyJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Start the digest notification jobs
     */
    fun start() {
        log.info("Starting digest notification jobs")
        
        // Start daily digest job - runs every day at 9 AM
        dailyJob = scope.launch {
            while (isActive) {
                try {
                    val now = LocalDateTime.now()
                    val nextRun = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
                    val adjustedNextRun = if (nextRun.isBefore(now)) {
                        nextRun.plusDays(1)
                    } else {
                        nextRun
                    }
                    
                    val delayMillis = Duration.between(now, adjustedNextRun).toMillis()
                    log.info("Daily digest scheduled for: {} (in {} minutes)", adjustedNextRun, delayMillis / 1000 / 60)
                    
                    delay(delayMillis)
                    
                    log.info("Running daily digest job")
                    matchService.processDigestNotifications(NotificationFrequency.DAILY)
                    log.info("Daily digest job completed")
                    
                } catch (e: CancellationException) {
                    log.info("Daily digest job cancelled")
                    throw e
                } catch (e: Exception) {
                    log.error("Error in daily digest job", e)
                    // Wait 1 hour before retrying on error
                    delay(60 * 60 * 1000)
                }
            }
        }
        
        // Start weekly digest job - runs every Monday at 9 AM
        weeklyJob = scope.launch {
            while (isActive) {
                try {
                    val now = LocalDateTime.now()
                    var nextRun = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
                    
                    // Find next Monday
                    val daysUntilMonday = (8 - now.dayOfWeek.value) % 7
                    nextRun = if (daysUntilMonday == 0 && nextRun.isAfter(now)) {
                        nextRun // Today is Monday and we haven't passed 9 AM
                    } else if (daysUntilMonday == 0) {
                        nextRun.plusWeeks(1) // Today is Monday but we passed 9 AM
                    } else {
                        nextRun.plusDays(daysUntilMonday.toLong())
                    }
                    
                    val delayMillis = Duration.between(now, nextRun).toMillis()
                    log.info("Weekly digest scheduled for: {} (in {} hours)", nextRun, delayMillis / 1000 / 60 / 60)
                    
                    delay(delayMillis)
                    
                    log.info("Running weekly digest job")
                    matchService.processDigestNotifications(NotificationFrequency.WEEKLY)
                    log.info("Weekly digest job completed")
                    
                } catch (e: CancellationException) {
                    log.info("Weekly digest job cancelled")
                    throw e
                } catch (e: Exception) {
                    log.error("Error in weekly digest job", e)
                    // Wait 1 hour before retrying on error
                    delay(60 * 60 * 1000)
                }
            }
        }
        
        log.info("Digest notification jobs started")
    }
    
    /**
     * Stop the digest notification jobs
     */
    fun stop() {
        log.info("Stopping digest notification jobs")
        dailyJob?.cancel()
        weeklyJob?.cancel()
        scope.cancel()
        log.info("Digest notification jobs stopped")
    }
    
    /**
     * Manually trigger daily digest (for testing)
     */
    suspend fun triggerDailyDigest() {
        log.info("Manually triggering daily digest")
        try {
            matchService.processDigestNotifications(NotificationFrequency.DAILY)
            log.info("Daily digest completed")
        } catch (e: Exception) {
            log.error("Error in daily digest", e)
        }
    }
    
    /**
     * Manually trigger weekly digest (for testing)
     */
    suspend fun triggerWeeklyDigest() {
        log.info("Manually triggering weekly digest")
        try {
            matchService.processDigestNotifications(NotificationFrequency.WEEKLY)
            log.info("Weekly digest completed")
        } catch (e: Exception) {
            log.error("Error in weekly digest", e)
        }
    }
}

/**
 * Singleton instance for managing the digest job lifecycle
 */
object DigestNotificationJobManager {
    private val log = LoggerFactory.getLogger(this::class.java)
    private var job: DigestNotificationJob? = null
    
    fun startJobs() {
        if (job == null) {
            job = DigestNotificationJob()
            job?.start()
        } else {
            log.warn("Digest notification jobs already running")
        }
    }
    
    fun stopJobs() {
        job?.stop()
        job = null
    }
    
    suspend fun triggerDailyDigest() {
        job?.triggerDailyDigest() ?: log.error("No job instance available for daily digest")
    }
    
    suspend fun triggerWeeklyDigest() {
        job?.triggerWeeklyDigest() ?: log.error("No job instance available for weekly digest")
    }
}
