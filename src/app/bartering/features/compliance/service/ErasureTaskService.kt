package app.bartering.features.compliance.service

import app.bartering.config.RetentionConfig
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.compliance.db.ComplianceErasureTasksTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit

class ErasureTaskService {

    suspend fun createDefaultErasureTasks(userId: String, requestedBy: String?): List<Long> {
        val now = Instant.now()
        val backupDueAt = now.plus(RetentionConfig.backupRetentionDays.toLong(), ChronoUnit.DAYS)

        val taskSpecs = listOf(
            Triple("local_uploads", "local", "uploads/images for user; verify local file purge"),
            Triple("cloud_media", "cloud", "Cloud object storage keys for user media"),
            Triple("backup_retention", "backup", "Mark user data for deletion-on-expiry in backups")
        )

        return dbQuery {
            taskSpecs.map { (taskType, scope, targetRef) ->
                ComplianceErasureTasksTable.insert {
                    it[ComplianceErasureTasksTable.userId] = userId
                    it[ComplianceErasureTasksTable.taskType] = taskType
                    it[ComplianceErasureTasksTable.status] = "pending"
                    it[ComplianceErasureTasksTable.storageScope] = scope
                    it[ComplianceErasureTasksTable.targetRef] = targetRef
                    it[ComplianceErasureTasksTable.requestedBy] = requestedBy
                    it[ComplianceErasureTasksTable.dueAt] = if (scope == "backup") backupDueAt else now.plus(7, ChronoUnit.DAYS)
                    it[ComplianceErasureTasksTable.createdAt] = now
                    it[ComplianceErasureTasksTable.updatedAt] = now
                }[ComplianceErasureTasksTable.id]
            }
        }
    }

    suspend fun listTasks(
        userId: String? = null,
        status: String? = null,
        limit: Int = 200
    ): List<ErasureTaskView> = dbQuery {
        val rows = ComplianceErasureTasksTable
            .selectAll()
            .orderBy(ComplianceErasureTasksTable.createdAt to SortOrder.DESC)
            .limit(limit * 5)
            .toList()

        rows.asSequence()
            .filter { userId == null || it[ComplianceErasureTasksTable.userId] == userId }
            .filter { status == null || it[ComplianceErasureTasksTable.status] == status }
            .take(limit)
            .map { it.toView() }
            .toList()
    }

    suspend fun markInProgress(taskId: Long, handledBy: String?): Boolean = dbQuery {
        ComplianceErasureTasksTable.update({ ComplianceErasureTasksTable.id eq taskId }) {
            it[status] = "in_progress"
            it[ComplianceErasureTasksTable.handledBy] = handledBy
            it[updatedAt] = Instant.now()
        } > 0
    }

    suspend fun markCompleted(taskId: Long, handledBy: String?, notes: String?): Boolean = dbQuery {
        val now = Instant.now()
        ComplianceErasureTasksTable.update({ ComplianceErasureTasksTable.id eq taskId }) {
            it[status] = "completed"
            it[ComplianceErasureTasksTable.handledBy] = handledBy
            if (notes != null) it[ComplianceErasureTasksTable.notes] = notes
            it[completedAt] = now
            it[updatedAt] = now
        } > 0
    }

    suspend fun markFailed(taskId: Long, handledBy: String?, notes: String?): Boolean = dbQuery {
        ComplianceErasureTasksTable.update({ ComplianceErasureTasksTable.id eq taskId }) {
            it[status] = "failed"
            it[ComplianceErasureTasksTable.handledBy] = handledBy
            if (notes != null) it[ComplianceErasureTasksTable.notes] = notes
            it[updatedAt] = Instant.now()
        } > 0
    }

    suspend fun countOverduePendingTasks(now: Instant = Instant.now()): Int = dbQuery {
        ComplianceErasureTasksTable
            .selectAll()
            .where {
                (ComplianceErasureTasksTable.dueAt less now) and
                    ((ComplianceErasureTasksTable.status eq "pending") or
                        (ComplianceErasureTasksTable.status eq "in_progress"))
            }
            .count()
            .toInt()
    }

    suspend fun countBackupTasksDueSoon(days: Long = 7): Int = dbQuery {
        val from = Instant.now()
        val to = from.plus(days, ChronoUnit.DAYS)

        ComplianceErasureTasksTable
            .selectAll()
            .where {
                (ComplianceErasureTasksTable.storageScope eq "backup") and
                    (ComplianceErasureTasksTable.status eq "pending") and
                    (ComplianceErasureTasksTable.dueAt greaterEq from) and
                    (ComplianceErasureTasksTable.dueAt less to)
            }
            .count()
            .toInt()
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toView() = ErasureTaskView(
        id = this[ComplianceErasureTasksTable.id],
        userId = this[ComplianceErasureTasksTable.userId],
        taskType = this[ComplianceErasureTasksTable.taskType],
        status = this[ComplianceErasureTasksTable.status],
        storageScope = this[ComplianceErasureTasksTable.storageScope],
        targetRef = this[ComplianceErasureTasksTable.targetRef],
        requestedBy = this[ComplianceErasureTasksTable.requestedBy],
        handledBy = this[ComplianceErasureTasksTable.handledBy],
        dueAt = this[ComplianceErasureTasksTable.dueAt],
        completedAt = this[ComplianceErasureTasksTable.completedAt],
        notes = this[ComplianceErasureTasksTable.notes],
        createdAt = this[ComplianceErasureTasksTable.createdAt],
        updatedAt = this[ComplianceErasureTasksTable.updatedAt]
    )
}

data class ErasureTaskView(
    val id: Long,
    val userId: String,
    val taskType: String,
    val status: String,
    val storageScope: String,
    val targetRef: String?,
    val requestedBy: String?,
    val handledBy: String?,
    val dueAt: Instant?,
    val completedAt: Instant?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
