package app.bartering.features.relationships.model

import kotlinx.serialization.Serializable

/**
 * Reasons for reporting a user
 */
enum class ReportReason(val value: String) {
    SPAM("spam"),
    HARASSMENT("harassment"),
    INAPPROPRIATE_CONTENT("inappropriate_content"),
    SCAM("scam"),
    FAKE_PROFILE("fake_profile"),
    IMPERSONATION("impersonation"),
    THREATENING_BEHAVIOR("threatening_behavior"),
    OTHER("other");

    companion object {
        fun fromString(value: String): ReportReason? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Context types for reports
 */
enum class ReportContextType(val value: String) {
    PROFILE("profile"),
    POSTING("posting"),
    CHAT("chat"),
    REVIEW("review"),
    GENERAL("general");

    companion object {
        fun fromString(value: String): ReportContextType? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Status of a report
 */
enum class ReportStatus(val value: String) {
    PENDING("pending"),
    UNDER_REVIEW("under_review"),
    REVIEWED("reviewed"),
    DISMISSED("dismissed"),
    ACTION_TAKEN("action_taken");

    companion object {
        fun fromString(value: String): ReportStatus? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Actions that can be taken on reports
 */
enum class ReportAction(val value: String) {
    WARNING("warning"),
    TEMPORARY_BAN("temporary_ban"),
    PERMANENT_BAN("permanent_ban"),
    CONTENT_REMOVED("content_removed"),
    ACCOUNT_RESTRICTED("account_restricted"),
    NONE("none");

    companion object {
        fun fromString(value: String): ReportAction? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Request to report a user
 */
@Serializable
data class UserReportRequest(
    val reporterUserId: String,
    val reportedUserId: String,
    val reportReason: String, // ReportReason enum value
    val description: String? = null,
    val contextType: String? = null, // ReportContextType enum value
    val contextId: String? = null // ID of posting, chat, review, etc.
)

/**
 * Response containing report information
 */
@Serializable
data class UserReportResponse(
    val id: String,
    val reporterUserId: String,
    val reportedUserId: String,
    val reportReason: String,
    val description: String? = null,
    val contextType: String? = null,
    val contextId: String? = null,
    val status: String,
    val reportedAt: String,
    val reviewedAt: String? = null,
    val actionTaken: String? = null
)

/**
 * Request to update report status (moderator only)
 */
@Serializable
data class UpdateReportStatusRequest(
    val reportId: String,
    val status: String, // ReportStatus enum value
    val actionTaken: String? = null, // ReportAction enum value
    val moderatorNotes: String? = null
)

/**
 * Statistics about reports for a user
 */
@Serializable
data class UserReportStats(
    val userId: String,
    val totalReportsReceived: Int = 0,
    val pendingReports: Int = 0,
    val actionsTaken: Int = 0,
    val lastReportedAt: String? = null
)

/**
 * Response for checking if user has already reported another user
 */
@Serializable
data class ReportCheckResponse(
    val hasReported: Boolean,
    val reportId: String? = null,
    val reportedAt: String? = null
)
