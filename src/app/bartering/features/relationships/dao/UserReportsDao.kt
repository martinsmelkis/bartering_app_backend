package app.bartering.features.relationships.dao

import app.bartering.features.relationships.model.*

/**
 * Data access interface for user reports
 */
interface UserReportsDao {
    /**
     * Create a new user report
     */
    suspend fun createReport(
        reporterUserId: String,
        reportedUserId: String,
        reportReason: ReportReason,
        description: String?,
        contextType: ReportContextType?,
        contextId: String?
    ): String?

    /**
     * Get a report by ID
     */
    suspend fun getReportById(reportId: String): UserReportResponse?

    /**
     * Get all reports filed by a user
     */
    suspend fun getReportsByReporter(reporterUserId: String): List<UserReportResponse>

    /**
     * Get all reports against a user
     */
    suspend fun getReportsAgainstUser(reportedUserId: String): List<UserReportResponse>

    /**
     * Get pending reports (for moderation)
     */
    suspend fun getPendingReports(limit: Int = 50): List<UserReportResponse>

    /**
     * Check if user has already reported another user
     */
    suspend fun hasReported(reporterUserId: String, reportedUserId: String): Boolean

    /**
     * Get report statistics for a user
     */
    suspend fun getUserReportStats(userId: String): UserReportStats

    /**
     * Update report status (moderator action)
     */
    suspend fun updateReportStatus(
        reportId: String,
        status: ReportStatus,
        reviewedBy: String,
        actionTaken: ReportAction?,
        moderatorNotes: String?
    ): Boolean

    /**
     * Get count of pending reports against a user
     */
    suspend fun getPendingReportCount(reportedUserId: String): Int

    /**
     * Get most recent reports against a user
     */
    suspend fun getRecentReportsAgainstUser(
        reportedUserId: String,
        limit: Int = 10
    ): List<UserReportResponse>

    /**
     * Dismiss a report (delete or mark as dismissed)
     */
    suspend fun dismissReport(reportId: String, reviewedBy: String, reason: String?): Boolean
}
