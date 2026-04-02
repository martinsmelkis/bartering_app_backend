package app.bartering.features.profile.service

import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.AttachmentDisposition
import app.bartering.features.notifications.model.EmailAttachment
import app.bartering.features.notifications.model.EmailNotification
import app.bartering.features.notifications.service.EmailService
import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.profile.dao.UserProfileDao
import app.bartering.features.relationships.dao.UserRelationshipsDao
import app.bartering.features.relationships.dao.UserReportsDao
import app.bartering.features.reviews.dao.BarterTransactionDao
import app.bartering.features.reviews.dao.BarterTransactionDto
import app.bartering.features.reviews.dao.BadgeWithTimestamp
import app.bartering.features.reviews.dao.ReputationDao
import app.bartering.features.reviews.dao.ReputationDto
import app.bartering.features.reviews.dao.ReviewDao
import app.bartering.features.reviews.dao.ReviewDto
import app.bartering.features.wallet.model.LedgerTransaction
import app.bartering.features.wallet.model.Wallet
import app.bartering.features.wallet.service.WalletService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GdprDataExportService(
    private val userProfileDao: UserProfileDao,
    private val postingDao: UserPostingDao,
    private val relationshipsDao: UserRelationshipsDao,
    private val userReportsDao: UserReportsDao,
    private val reviewDao: ReviewDao,
    private val transactionDao: BarterTransactionDao,
    private val reputationDao: ReputationDao,
    private val notificationPreferencesDao: NotificationPreferencesDao,
    private val walletService: WalletService,
    private val emailService: EmailService
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val utcFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    suspend fun exportAndSendToUser(userId: String): ExportResult {
        val contacts = notificationPreferencesDao.getUserContacts(userId)
        val email = contacts?.email

        if (email.isNullOrBlank()) {
            return ExportResult(
                success = false,
                message = "No email address configured for this account. Please set your email in notification contacts first."
            )
        }

        val now = Instant.now()
        val exportTimestamp = utcFormatter.format(now)
        val zipFilename = "barter_data_export_${userId.take(8)}_$exportTimestamp.zip"

        val profile = userProfileDao.getProfile(userId)
        val createdAt = userProfileDao.getUserCreatedAt(userId)
        val postings = postingDao.getUserPostings(userId, includeExpired = true)
        val relationships = relationshipsDao.getUserRelationships(userId)
        val reportsByUser = userReportsDao.getReportsByReporter(userId)
        val reportsAgainstUser = userReportsDao.getReportsAgainstUser(userId)
        val reviewsAuthored = reviewDao.getUserReviews(userId)
        val transactions = transactionDao.getUserTransactions(userId)
        val reputation = reputationDao.getReputation(userId)
        val badges = reputationDao.getUserBadgesWithTimestamps(userId)
        val notificationContacts = notificationPreferencesDao.getUserContacts(userId)
        val attributePreferences = notificationPreferencesDao.getAllAttributePreferences(userId)
        val matchHistory = notificationPreferencesDao.getUserMatches(userId, unviewedOnly = false, limit = 1000)
        val wallet = walletService.getWallet(userId)
        val walletTransactions = walletService.getTransactions(userId, limit = 1000, offset = 0)

        val manifest = ExportManifest(
            generatedAt = now.toString(),
            generatedForUserId = userId,
            format = "zip+json",
            files = listOf(
                "manifest.json",
                "profile/profile.json",
                "profile/account_meta.json",
                "postings/postings.json",
                "relationships/relationships.json",
                "relationships/reports_by_user.json",
                "relationships/reports_against_user.json",
                "reviews/reviews_authored.json",
                "reviews/transactions.json",
                "reviews/reputation.json",
                "notifications/contacts.json",
                "notifications/attribute_preferences.json",
                "notifications/match_history.json",
                "wallet/wallet.json",
                "wallet/transactions.json"
            )
        )

        val zipBytes = createZip(
            mapOf(
                "manifest.json" to json.encodeToString(ExportManifest.serializer(), manifest),
                "profile/profile.json" to json.encodeToString(NullableProfileExport.serializer(), NullableProfileExport(profile)),
                "profile/account_meta.json" to json.encodeToString(AccountMetaExport.serializer(), AccountMetaExport(userId, createdAt?.toString())),
                "postings/postings.json" to json.encodeToString(PostingsExport.serializer(), PostingsExport(postings)),
                "relationships/relationships.json" to json.encodeToString(RelationshipsExport.serializer(), RelationshipsExport(relationships)),
                "relationships/reports_by_user.json" to json.encodeToString(UserReportsExport.serializer(), UserReportsExport(reportsByUser)),
                "relationships/reports_against_user.json" to json.encodeToString(UserReportsExport.serializer(), UserReportsExport(reportsAgainstUser)),
                "reviews/reviews_authored.json" to json.encodeToString(reviewsToJson(reviewsAuthored)),
                "reviews/transactions.json" to json.encodeToString(transactionsToJson(transactions)),
                "reviews/reputation.json" to json.encodeToString(reputationToJson(reputation, badges)),
                "notifications/contacts.json" to json.encodeToString(NotificationContactsExport.serializer(), NotificationContactsExport(notificationContacts)),
                "notifications/attribute_preferences.json" to json.encodeToString(AttributePreferencesExport.serializer(), AttributePreferencesExport(attributePreferences)),
                "notifications/match_history.json" to json.encodeToString(MatchHistoryExport.serializer(), MatchHistoryExport(matchHistory)),
                "wallet/wallet.json" to json.encodeToString(walletToJson(wallet)),
                "wallet/transactions.json" to json.encodeToString(walletTransactionsToJson(walletTransactions))
            )
        )

        val attachment = EmailAttachment(
            filename = zipFilename,
            content = Base64.getEncoder().encodeToString(zipBytes),
            contentType = "application/zip",
            disposition = AttachmentDisposition.ATTACHMENT
        )

        val message = EmailNotification(
            to = listOf(email),
            from = System.getenv("MAILJET_FROM_EMAIL") ?: "info@bartering.app",
            fromName = "Bartering Privacy",
            subject = "Your GDPR Data Export",
            textBody = "Your requested data export is attached as a ZIP file with JSON documents.",
            htmlBody = """
                <p>Hello,</p>
                <p>Your requested GDPR data export is attached to this email as a ZIP file.</p>
                <p>The export contains machine-readable JSON files for your profile, postings, reviews, relationships, notifications, and wallet data.</p>
                <p>Generated at: ${now}</p>
            """.trimIndent(),
            attachments = listOf(attachment),
            tags = listOf("gdpr", "data_export"),
            metadata = mapOf("userId" to userId, "exportTimestamp" to now.toString())
        )

        val sendResult = emailService.sendEmail(message)

        return if (sendResult.success) {
            log.info("GDPR data export sent successfully to user {}", userId)
            ExportResult(
                success = true,
                message = "Data export sent to your configured email address.",
                artifactSha256 = sha256Hex(zipBytes),
                artifactSizeBytes = zipBytes.size.toLong()
            )
        } else {
            log.warn("Failed to send GDPR data export email for user {}: {}", userId, sendResult.errorMessage)
            ExportResult(
                success = false,
                message = sendResult.errorMessage ?: "Failed to send data export email.",
                artifactSha256 = null,
                artifactSizeBytes = zipBytes.size.toLong()
            )
        }
    }

    private fun createZip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun reviewsToJson(reviews: List<ReviewDto>): JsonObject = buildJsonObject {
        put("reviews", buildJsonArray {
            reviews.forEach { review ->
                add(buildJsonObject {
                    put("id", review.id)
                    put("transactionId", review.transactionId)
                    put("reviewerId", review.reviewerId)
                    put("targetUserId", review.targetUserId)
                    put("rating", review.rating)
                    put("reviewText", review.reviewText?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("transactionStatus", review.transactionStatus.value)
                    put("reviewWeight", review.reviewWeight)
                    put("isVisible", review.isVisible)
                    put("submittedAt", review.submittedAt.toString())
                    put("revealedAt", review.revealedAt?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("isVerified", review.isVerified)
                    put("moderationStatus", review.moderationStatus?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
    }

    private fun transactionsToJson(transactions: List<BarterTransactionDto>): JsonObject = buildJsonObject {
        put("transactions", buildJsonArray {
            transactions.forEach { tx ->
                add(buildJsonObject {
                    put("id", tx.id)
                    put("user1Id", tx.user1Id)
                    put("user2Id", tx.user2Id)
                    put("initiatedAt", tx.initiatedAt.toString())
                    put("completedAt", tx.completedAt?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("status", tx.status.value)
                    put("estimatedValue", tx.estimatedValue?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("locationConfirmed", tx.locationConfirmed)
                    put("riskScore", tx.riskScore?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
    }

    private fun reputationToJson(reputation: ReputationDto?, badges: List<BadgeWithTimestamp>): JsonObject = buildJsonObject {
        put("reputation", if (reputation == null) JsonNull else buildJsonObject {
            put("userId", reputation.userId)
            put("averageRating", reputation.averageRating)
            put("totalReviews", reputation.totalReviews)
            put("verifiedReviews", reputation.verifiedReviews)
            put("tradeDiversityScore", reputation.tradeDiversityScore)
            put("trustLevel", reputation.trustLevel.value)
            put("lastUpdated", reputation.lastUpdated.toString())
        })

        put("badges", buildJsonArray {
            badges.forEach { badge ->
                add(buildJsonObject {
                    put("badge", badge.badge.name)
                    put("earnedAt", badge.earnedAt.toString())
                    put("expiresAt", badge.expiresAt?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
    }

    private fun walletToJson(wallet: Wallet): JsonObject = buildJsonObject {
        put("userId", wallet.userId)
        put("availableBalance", wallet.availableBalance)
        put("lockedBalance", wallet.lockedBalance)
        put("totalEarned", wallet.totalEarned)
        put("totalSpent", wallet.totalSpent)
        put("updatedAt", wallet.updatedAt.toString())
    }

    private fun walletTransactionsToJson(transactions: List<LedgerTransaction>): JsonObject = buildJsonObject {
        put("transactions", buildJsonArray {
            transactions.forEach { tx ->
                add(buildJsonObject {
                    put("id", tx.id)
                    put("type", tx.type.value)
                    put("amount", tx.amount)
                    put("fromUserId", tx.fromUserId?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("toUserId", tx.toUserId?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("externalRef", tx.externalRef?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("metadataJson", tx.metadataJson?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("createdAt", tx.createdAt.toString())
                })
            }
        })
    }
}

@Serializable
data class ExportResult(
    val success: Boolean,
    val message: String,
    val artifactSha256: String? = null,
    val artifactSizeBytes: Long? = null
)

@Serializable
data class ExportManifest(
    val generatedAt: String,
    val generatedForUserId: String,
    val format: String,
    val files: List<String>
)

@Serializable
data class NullableProfileExport(
    val profile: app.bartering.features.profile.model.UserProfile?
)

@Serializable
data class AccountMetaExport(
    val userId: String,
    val createdAt: String?
)

@Serializable
data class PostingsExport(
    val postings: List<app.bartering.features.postings.model.UserPosting>
)

@Serializable
data class RelationshipsExport(
    val relationships: app.bartering.features.relationships.model.UserRelationshipsResponse
)

@Serializable
data class UserReportsExport(
    val reports: List<app.bartering.features.relationships.model.UserReportResponse>
)

@Serializable
data class NotificationContactsExport(
    val contacts: app.bartering.features.notifications.model.UserNotificationContacts?
)

@Serializable
data class AttributePreferencesExport(
    val preferences: List<app.bartering.features.notifications.model.AttributeNotificationPreference>
)

@Serializable
data class MatchHistoryExport(
    val matches: List<app.bartering.features.notifications.model.MatchHistoryEntry>
)
