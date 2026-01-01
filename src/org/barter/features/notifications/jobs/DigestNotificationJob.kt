package org.barter.features.notifications.jobs

import kotlinx.coroutines.*
import org.barter.features.notifications.model.NotificationFrequency
import org.barter.features.notifications.service.MatchNotificationService
import org.koin.java.KoinJavaComponent.inject
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
    
    private val matchService: MatchNotificationService by inject(MatchNotificationService::class.java)
    private var dailyJob: Job? = null
    private var weeklyJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Start the digest notification jobs
     */
    fun start() {
        println("📬 Starting digest notification jobs...")
        
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
                    println("📬 Daily digest scheduled for: $adjustedNextRun (in ${delayMillis / 1000 / 60} minutes)")
                    
                    delay(delayMillis)
                    
                    println("📬 Running daily digest job...")
                    matchService.processDigestNotifications(NotificationFrequency.DAILY)
                    println("✅ Daily digest job completed")
                    
                } catch (e: CancellationException) {
                    println("📬 Daily digest job cancelled")
                    throw e
                } catch (e: Exception) {
                    println("❌ Error in daily digest job: ${e.message}")
                    e.printStackTrace()
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
                    println("📬 Weekly digest scheduled for: $nextRun (in ${delayMillis / 1000 / 60 / 60} hours)")
                    
                    delay(delayMillis)
                    
                    println("📬 Running weekly digest job...")
                    matchService.processDigestNotifications(NotificationFrequency.WEEKLY)
                    println("✅ Weekly digest job completed")
                    
                } catch (e: CancellationException) {
                    println("📬 Weekly digest job cancelled")
                    throw e
                } catch (e: Exception) {
                    println("❌ Error in weekly digest job: ${e.message}")
                    e.printStackTrace()
                    // Wait 1 hour before retrying on error
                    delay(60 * 60 * 1000)
                }
            }
        }
        
        println("✅ Digest notification jobs started")
    }
    
    /**
     * Stop the digest notification jobs
     */
    fun stop() {
        println("📬 Stopping digest notification jobs...")
        dailyJob?.cancel()
        weeklyJob?.cancel()
        scope.cancel()
        println("✅ Digest notification jobs stopped")
    }
    
    /**
     * Manually trigger daily digest (for testing)
     */
    suspend fun triggerDailyDigest() {
        println("📬 Manually triggering daily digest...")
        try {
            matchService.processDigestNotifications(NotificationFrequency.DAILY)
            println("✅ Daily digest completed")
        } catch (e: Exception) {
            println("❌ Error in daily digest: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Manually trigger weekly digest (for testing)
     */
    suspend fun triggerWeeklyDigest() {
        println("📬 Manually triggering weekly digest...")
        try {
            matchService.processDigestNotifications(NotificationFrequency.WEEKLY)
            println("✅ Weekly digest completed")
        } catch (e: Exception) {
            println("❌ Error in weekly digest: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * Singleton instance for managing the digest job lifecycle
 */
object DigestNotificationJobManager {
    private var job: DigestNotificationJob? = null
    
    fun startJobs() {
        if (job == null) {
            job = DigestNotificationJob()
            job?.start()
        } else {
            println("⚠️  Digest notification jobs already running")
        }
    }
    
    fun stopJobs() {
        job?.stop()
        job = null
    }
    
    suspend fun triggerDailyDigest() {
        job?.triggerDailyDigest() ?: println("❌ No job instance available")
    }
    
    suspend fun triggerWeeklyDigest() {
        job?.triggerWeeklyDigest() ?: println("❌ No job instance available")
    }
}
