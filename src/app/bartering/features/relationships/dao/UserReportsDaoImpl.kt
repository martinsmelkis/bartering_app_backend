package app.bartering.features.relationships.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.relationships.db.UserReportsTable
import app.bartering.features.relationships.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

class UserReportsDaoImpl : UserReportsDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    override suspend fun createReport(
        reporterUserId: String,
        reportedUserId: String,
        reportReason: ReportReason,
        description: String?,
        contextType: ReportContextType?,
        contextId: String?
    ): String? = dbQuery {
        try {
            val reportId = UUID.randomUUID().toString()
            UserReportsTable.insert {
                it[id] = reportId
                it[UserReportsTable.reporterUserId] = reporterUserId
                it[UserReportsTable.reportedUserId] = reportedUserId
                it[UserReportsTable.reportReason] = reportReason.value
                it[UserReportsTable.description] = description
                it[UserReportsTable.contextType] = contextType?.value
                it[UserReportsTable.contextId] = contextId
                it[status] = ReportStatus.PENDING.value
                it[reportedAt] = Instant.now()
            }
            reportId
        } catch (e: Exception) {
            log.error("Error creating report from {} against {}", reporterUserId, reportedUserId, e)
            null
        }
    }

    override suspend fun getReportById(reportId: String): UserReportResponse? = dbQuery {
        UserReportsTable
            .selectAll()
            .where { UserReportsTable.id eq reportId }
            .map { rowToResponse(it) }
            .singleOrNull()
    }

    override suspend fun getReportsByReporter(reporterUserId: String): List<UserReportResponse> =
        dbQuery {
            UserReportsTable
                .selectAll()
                .where { UserReportsTable.reporterUserId eq reporterUserId }
                .orderBy(UserReportsTable.reportedAt, SortOrder.DESC)
                .map { rowToResponse(it) }
        }

    override suspend fun getReportsAgainstUser(reportedUserId: String): List<UserReportResponse> =
        dbQuery {
            UserReportsTable
                .selectAll()
                .where { UserReportsTable.reportedUserId eq reportedUserId }
                .orderBy(UserReportsTable.reportedAt, SortOrder.DESC)
                .map { rowToResponse(it) }
        }

    override suspend fun getPendingReports(limit: Int): List<UserReportResponse> = dbQuery {
        UserReportsTable
            .selectAll()
            .where { UserReportsTable.status eq ReportStatus.PENDING.value }
            .orderBy(UserReportsTable.reportedAt, SortOrder.DESC)
            .limit(limit)
            .map { rowToResponse(it) }
    }

    override suspend fun hasReported(
        reporterUserId: String,
        reportedUserId: String
    ): Boolean = dbQuery {
        UserReportsTable
            .selectAll()
            .where {
                (UserReportsTable.reporterUserId eq reporterUserId) and
                        (UserReportsTable.reportedUserId eq reportedUserId)
            }
            .count() > 0
    }

    override suspend fun getUserReportStats(userId: String): UserReportStats = dbQuery {
        val totalReports = UserReportsTable
            .selectAll()
            .where { UserReportsTable.reportedUserId eq userId }
            .count()

        val pendingReports = UserReportsTable
            .selectAll()
            .where {
                (UserReportsTable.reportedUserId eq userId) and
                        (UserReportsTable.status eq ReportStatus.PENDING.value)
            }
            .count()

        val actionsTaken = UserReportsTable
            .selectAll()
            .where {
                (UserReportsTable.reportedUserId eq userId) and
                        (UserReportsTable.status eq ReportStatus.ACTION_TAKEN.value)
            }
            .count()

        val lastReport = UserReportsTable
            .selectAll()
            .where { UserReportsTable.reportedUserId eq userId }
            .orderBy(UserReportsTable.reportedAt, SortOrder.DESC)
            .limit(1)
            .map { it[UserReportsTable.reportedAt] }
            .firstOrNull()

        UserReportStats(
            userId = userId,
            totalReportsReceived = totalReports.toInt(),
            pendingReports = pendingReports.toInt(),
            actionsTaken = actionsTaken.toInt(),
            lastReportedAt = lastReport?.let { DateTimeFormatter.ISO_INSTANT.format(it) }
        )
    }

    override suspend fun updateReportStatus(
        reportId: String,
        status: ReportStatus,
        reviewedBy: String,
        actionTaken: ReportAction?,
        moderatorNotes: String?
    ): Boolean = dbQuery {
        try {
            UserReportsTable.update({ UserReportsTable.id eq reportId }) {
                it[UserReportsTable.status] = status.value
                it[reviewedAt] = Instant.now()
                it[UserReportsTable.reviewedBy] = reviewedBy
                it[UserReportsTable.actionTaken] = actionTaken?.value
                it[UserReportsTable.moderatorNotes] = moderatorNotes
            } > 0
        } catch (e: Exception) {
            log.error("Error updating report status for reportId={}", reportId, e)
            false
        }
    }

    override suspend fun getPendingReportCount(reportedUserId: String): Int = dbQuery {
        UserReportsTable
            .selectAll()
            .where {
                (UserReportsTable.reportedUserId eq reportedUserId) and
                        (UserReportsTable.status eq ReportStatus.PENDING.value)
            }
            .count()
            .toInt()
    }

    override suspend fun getRecentReportsAgainstUser(
        reportedUserId: String,
        limit: Int
    ): List<UserReportResponse> = dbQuery {
        UserReportsTable
            .selectAll()
            .where { UserReportsTable.reportedUserId eq reportedUserId }
            .orderBy(UserReportsTable.reportedAt, SortOrder.DESC)
            .limit(limit)
            .map { rowToResponse(it) }
    }

    override suspend fun dismissReport(
        reportId: String,
        reviewedBy: String,
        reason: String?
    ): Boolean = dbQuery {
        try {
            UserReportsTable.update({ UserReportsTable.id eq reportId }) {
                it[status] = ReportStatus.DISMISSED.value
                it[reviewedAt] = Instant.now()
                it[UserReportsTable.reviewedBy] = reviewedBy
                it[moderatorNotes] = reason
            } > 0
        } catch (e: Exception) {
            log.error("Error dismissing report reportId={}", reportId, e)
            false
        }
    }

    private fun rowToResponse(row: ResultRow): UserReportResponse {
        return UserReportResponse(
            id = row[UserReportsTable.id],
            reporterUserId = row[UserReportsTable.reporterUserId],
            reportedUserId = row[UserReportsTable.reportedUserId],
            reportReason = row[UserReportsTable.reportReason],
            description = row[UserReportsTable.description],
            contextType = row[UserReportsTable.contextType],
            contextId = row[UserReportsTable.contextId],
            status = row[UserReportsTable.status],
            reportedAt = DateTimeFormatter.ISO_INSTANT.format(row[UserReportsTable.reportedAt]),
            reviewedAt = row[UserReportsTable.reviewedAt]?.let {
                DateTimeFormatter.ISO_INSTANT.format(it)
            },
            actionTaken = row[UserReportsTable.actionTaken]
        )
    }
}
