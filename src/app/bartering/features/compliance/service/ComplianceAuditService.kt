package app.bartering.features.compliance.service

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.compliance.db.ComplianceAuditLogTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Instant

class ComplianceAuditService {
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun logEvent(
        actorType: String,
        actorId: String? = null,
        eventType: String,
        entityType: String? = null,
        entityId: String? = null,
        purpose: String? = null,
        outcome: String,
        requestId: String? = null,
        ipHash: String? = null,
        deviceIdHash: String? = null,
        dsrRequestId: Long? = null,
        details: Map<String, String>? = null,
        createdAt: Instant = Instant.now()
    ) {
        try {
            dbQuery {
                ComplianceAuditLogTable.insert {
                    it[ComplianceAuditLogTable.actorType] = actorType
                    it[ComplianceAuditLogTable.actorId] = actorId
                    it[ComplianceAuditLogTable.eventType] = eventType
                    it[ComplianceAuditLogTable.entityType] = entityType
                    it[ComplianceAuditLogTable.entityId] = entityId
                    it[ComplianceAuditLogTable.purpose] = purpose
                    it[ComplianceAuditLogTable.outcome] = outcome
                    it[ComplianceAuditLogTable.requestId] = requestId
                    it[ComplianceAuditLogTable.ipHash] = ipHash
                    it[ComplianceAuditLogTable.deviceIdHash] = deviceIdHash
                    it[ComplianceAuditLogTable.dsrRequestId] = dsrRequestId
                    it[ComplianceAuditLogTable.detailsJson] = details?.let { d -> Json.encodeToString(d) }
                    it[ComplianceAuditLogTable.createdAt] = createdAt
                }
            }
        } catch (e: Exception) {
            // Never fail business flows because audit writing failed.
            log.warn("Failed to write compliance audit event {}", eventType, e)
        }
    }

    suspend fun listAuditEvents(
        actorId: String? = null,
        eventType: String? = null,
        dsrRequestId: Long? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 200
    ): List<ComplianceAuditEventView> = dbQuery {
        val rows = ComplianceAuditLogTable
            .selectAll()
            .orderBy(ComplianceAuditLogTable.createdAt to SortOrder.DESC)
            .limit(limit * 5)
            .toList()

        rows
            .asSequence()
            .filter { actorId == null || it[ComplianceAuditLogTable.actorId] == actorId }
            .filter { eventType == null || it[ComplianceAuditLogTable.eventType] == eventType }
            .filter { dsrRequestId == null || it[ComplianceAuditLogTable.dsrRequestId] == dsrRequestId }
            .filter { from == null || it[ComplianceAuditLogTable.createdAt] >= from }
            .filter { to == null || it[ComplianceAuditLogTable.createdAt] <= to }
            .take(limit)
            .map {
                ComplianceAuditEventView(
                    id = it[ComplianceAuditLogTable.id],
                    actorType = it[ComplianceAuditLogTable.actorType],
                    actorId = it[ComplianceAuditLogTable.actorId],
                    eventType = it[ComplianceAuditLogTable.eventType],
                    entityType = it[ComplianceAuditLogTable.entityType],
                    entityId = it[ComplianceAuditLogTable.entityId],
                    purpose = it[ComplianceAuditLogTable.purpose],
                    outcome = it[ComplianceAuditLogTable.outcome],
                    requestId = it[ComplianceAuditLogTable.requestId],
                    dsrRequestId = it[ComplianceAuditLogTable.dsrRequestId],
                    createdAt = it[ComplianceAuditLogTable.createdAt],
                    details = it[ComplianceAuditLogTable.detailsJson]
                        ?.let { json -> Json.parseToJsonElement(json).jsonObject }
                )
            }
            .toList()
    }

    suspend fun summarizeEventsByType(since: Instant): Map<String, Int> = dbQuery {
        ComplianceAuditLogTable
            .selectAll()
            .where { ComplianceAuditLogTable.createdAt greaterEq since }
            .groupingBy { it[ComplianceAuditLogTable.eventType] }
            .eachCount()
    }
}

data class ComplianceAuditEventView(
    val id: Long,
    val actorType: String,
    val actorId: String?,
    val eventType: String,
    val entityType: String?,
    val entityId: String?,
    val purpose: String?,
    val outcome: String,
    val requestId: String?,
    val dsrRequestId: Long?,
    val createdAt: Instant,
    val details: JsonObject?
)
