package app.bartering.features.postings.service

import app.bartering.features.notifications.model.NotificationData
import app.bartering.features.notifications.model.NotificationPriority
import app.bartering.features.notifications.model.PushNotification
import app.bartering.features.notifications.service.NotificationOrchestrator
import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.postings.model.UserPosting
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PostingExpiryReminderService(
    private val postingDao: UserPostingDao,
    private val notificationOrchestrator: NotificationOrchestrator
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val publicUrl = (System.getenv("PUBLIC_URL") ?: "https://bartering.app").trimEnd('/')

    suspend fun processDueReminders(limit: Int = DEFAULT_BATCH_SIZE): Int {
        val duePostings = postingDao.getPostingsDueForExpiryReminder(REMINDER_WINDOW_HOURS, limit)
        if (duePostings.isEmpty()) {
            return 0
        }

        var sentCount = 0
        for (posting in duePostings) {
            try {
                val expiresAt = posting.expiresAt ?: continue
                val notification = buildNotification(posting, expiresAt)
                val pushTemplate = PushNotification(
                    tokens = emptyList(),
                    notification = notification,
                    priority = NotificationPriority.HIGH,
                    ttl = REMINDER_WINDOW_HOURS.toInt() * 60 * 60,
                    collapseKey = "posting_expiry_${posting.id}",
                    data = notification.data
                )

                val results = notificationOrchestrator.sendNotification(
                    userId = posting.userId,
                    notification = notification,
                    pushNotification = pushTemplate,
                    includeWebSocket = true,
                    requireMarketingConsentForEmail = false
                )

                val delivered = results.values.any { it.success }
                if (delivered && postingDao.markExpiryReminderSent(posting.id, posting.userId, expiresAt)) {
                    sentCount++
                } else if (!delivered) {
                    log.warn("Posting expiry reminder for postingId={} produced no successful delivery", posting.id)
                }
            } catch (e: Exception) {
                log.error("Failed to send posting expiry reminder for postingId={}", posting.id, e)
            }
        }

        return sentCount
    }

    private fun buildNotification(posting: UserPosting, expiresAt: Instant): NotificationData {
        val hoursRemaining = Duration.between(Instant.now(), expiresAt).toHours().coerceAtLeast(0)
        val kind = if (posting.isOffer) "listing" else "posting"
        val actionPath = "/postings/${posting.id}/edit"
        val formattedExpiry = EXPIRY_FORMATTER.format(expiresAt)

        return NotificationData(
            title = "Renew your $kind soon",
            body = "${posting.title} expires in about ${hoursRemaining.coerceAtMost(24)} hours. Renew it to keep it visible.",
            imageUrl = posting.imageUrls.firstOrNull(),
            actionUrl = "$publicUrl$actionPath",
            data = mapOf(
                "type" to "posting_expiry_reminder",
                "postingId" to posting.id,
                "postingUserId" to posting.userId,
                "postingTitle" to posting.title,
                "postingImageUrl" to (posting.imageUrls.firstOrNull() ?: ""),
                "expiresAt" to expiresAt.toString(),
                "expiresAtFormatted" to formattedExpiry,
                "hoursRemaining" to hoursRemaining.toString(),
                "action" to "renew_posting",
                "actionUrl" to actionPath
            )
        )
    }

    companion object {
        private const val REMINDER_WINDOW_HOURS = 24L
        private const val DEFAULT_BATCH_SIZE = 100
        private val EXPIRY_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm z")
            .withZone(ZoneId.systemDefault())
    }
}
