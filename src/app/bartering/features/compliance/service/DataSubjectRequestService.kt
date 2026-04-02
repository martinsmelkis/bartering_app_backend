package app.bartering.features.compliance.service

import app.bartering.config.RetentionConfig
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.compliance.db.DataSubjectRequestsTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit

class DataSubjectRequestService {

    suspend fun createRequest(
        userId: String,
        requestType: String,
        requestedBy: String?,
        requestSource: String = "user",
        reason: String? = null,
        notes: String? = null,
        dueAt: Instant? = null
    ): Long = dbQuery {
        val now = Instant.now()
        val normalizedType = requestType.lowercase().trim()
        val normalizedSource = requestSource.lowercase().trim()
        val defaultDueAt = now.plus(RetentionConfig.dsarSlaDays.toLong(), ChronoUnit.DAYS)

        DataSubjectRequestsTable.insert {
            it[DataSubjectRequestsTable.userId] = userId
            it[DataSubjectRequestsTable.requestType] = normalizedType
            it[DataSubjectRequestsTable.status] = "received"
            it[DataSubjectRequestsTable.requestedBy] = requestedBy
            it[DataSubjectRequestsTable.requestSource] = normalizedSource
            it[DataSubjectRequestsTable.reason] = reason
            it[DataSubjectRequestsTable.notes] = notes
            it[DataSubjectRequestsTable.dueAt] = dueAt ?: defaultDueAt
            it[DataSubjectRequestsTable.createdAt] = now
            it[DataSubjectRequestsTable.updatedAt] = now
        }[DataSubjectRequestsTable.id]
    }

    suspend fun updateStatus(
        requestId: Long,
        status: String,
        handledBy: String?,
        notes: String? = null,
        rejectionReason: String? = null
    ): Boolean = dbQuery {
        val now = Instant.now()
        val normalizedStatus = status.lowercase().trim()

        DataSubjectRequestsTable.update({ DataSubjectRequestsTable.id eq requestId }) {
            it[DataSubjectRequestsTable.status] = normalizedStatus
            it[DataSubjectRequestsTable.handledBy] = handledBy
            if (notes != null) it[DataSubjectRequestsTable.notes] = notes
            if (rejectionReason != null) it[DataSubjectRequestsTable.rejectionReason] = rejectionReason
            if (normalizedStatus in setOf("completed", "rejected", "cancelled")) {
                it[DataSubjectRequestsTable.completedAt] = now
            }
            it[DataSubjectRequestsTable.updatedAt] = now
        } > 0
    }

    suspend fun completeRequest(
        requestId: Long,
        handledBy: String?,
        notes: String? = null
    ): Boolean = updateStatus(
        requestId = requestId,
        status = "completed",
        handledBy = handledBy,
        notes = notes
    )

    suspend fun getRequest(requestId: Long): DataSubjectRequestView? = dbQuery {
        DataSubjectRequestsTable
            .selectAll()
            .where { DataSubjectRequestsTable.id eq requestId }
            .limit(1)
            .map { it.toView() }
            .firstOrNull()
    }

    suspend fun listRequests(
        userId: String? = null,
        status: String? = null,
        requestType: String? = null,
        limit: Int = 200
    ): List<DataSubjectRequestView> = dbQuery {
        val rows = DataSubjectRequestsTable
            .selectAll()
            .orderBy(DataSubjectRequestsTable.createdAt to SortOrder.DESC)
            .limit(limit * 5)
            .toList()

        rows.asSequence()
            .filter { userId == null || it[DataSubjectRequestsTable.userId] == userId }
            .filter { status == null || it[DataSubjectRequestsTable.status] == status }
            .filter { requestType == null || it[DataSubjectRequestsTable.requestType] == requestType }
            .take(limit)
            .map { it.toView() }
            .toList()
    }

    suspend fun listOverdueRequests(limit: Int = 200): List<DataSubjectRequestView> = dbQuery {
        val now = Instant.now()
        DataSubjectRequestsTable
            .selectAll()
            .where {
                (DataSubjectRequestsTable.dueAt less now) and
                    (DataSubjectRequestsTable.status eq "received" or
                        (DataSubjectRequestsTable.status eq "in_progress"))
            }
            .orderBy(DataSubjectRequestsTable.dueAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toView() }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toView() = DataSubjectRequestView(
        id = this[DataSubjectRequestsTable.id],
        userId = this[DataSubjectRequestsTable.userId],
        requestType = this[DataSubjectRequestsTable.requestType],
        status = this[DataSubjectRequestsTable.status],
        requestedBy = this[DataSubjectRequestsTable.requestedBy],
        handledBy = this[DataSubjectRequestsTable.handledBy],
        requestSource = this[DataSubjectRequestsTable.requestSource],
        reason = this[DataSubjectRequestsTable.reason],
        notes = this[DataSubjectRequestsTable.notes],
        rejectionReason = this[DataSubjectRequestsTable.rejectionReason],
        dueAt = this[DataSubjectRequestsTable.dueAt],
        completedAt = this[DataSubjectRequestsTable.completedAt],
        createdAt = this[DataSubjectRequestsTable.createdAt],
        updatedAt = this[DataSubjectRequestsTable.updatedAt]
    )
}

data class DataSubjectRequestView(
    val id: Long,
    val userId: String,
    val requestType: String,
    val status: String,
    val requestedBy: String?,
    val handledBy: String?,
    val requestSource: String,
    val reason: String?,
    val notes: String?,
    val rejectionReason: String?,
    val dueAt: Instant,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)
