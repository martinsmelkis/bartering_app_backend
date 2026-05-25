package app.bartering.features.nearbyalerts.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.nearbyalerts.db.NearbyUserAlertsTable
import app.bartering.features.nearbyalerts.model.NearbyUserAlert
import app.bartering.features.nearbyalerts.model.UpsertNearbyUserAlertRequest
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

class NearbyUserAlertsDaoImpl : NearbyUserAlertsDao {

    override suspend fun getAlertForUser(userId: String): NearbyUserAlert? = dbQuery {
        NearbyUserAlertsTable
            .selectAll()
            .where { NearbyUserAlertsTable.userId eq userId }
            .map(::rowToAlert)
            .singleOrNull()
    }

    override suspend fun upsertAlert(userId: String, request: UpsertNearbyUserAlertRequest): NearbyUserAlert = dbQuery {
        val existing = NearbyUserAlertsTable
            .selectAll()
            .where { NearbyUserAlertsTable.userId eq userId }
            .map(::rowToAlert)
            .singleOrNull()

        val now = Instant.now()
        if (existing == null) {
            val id = UUID.randomUUID().toString()
            NearbyUserAlertsTable.insert {
                it[NearbyUserAlertsTable.id] = id
                it[NearbyUserAlertsTable.userId] = userId
                it[latitude] = request.latitude
                it[longitude] = request.longitude
                it[radiusMeters] = request.radiusMeters
                it[minUserCount] = request.minUserCount
                it[enabled] = request.enabled
                it[lastNearbyUserCount] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        } else {
            NearbyUserAlertsTable.update({ NearbyUserAlertsTable.id eq existing.id }) {
                it[latitude] = request.latitude
                it[longitude] = request.longitude
                it[radiusMeters] = request.radiusMeters
                it[minUserCount] = request.minUserCount
                it[enabled] = request.enabled
                it[updatedAt] = now
            }
        }

        NearbyUserAlertsTable
            .selectAll()
            .where { NearbyUserAlertsTable.userId eq userId }
            .map(::rowToAlert)
            .single()
    }

    override suspend fun setAlertEnabled(userId: String, enabled: Boolean): NearbyUserAlert? = dbQuery {
        NearbyUserAlertsTable.update({ NearbyUserAlertsTable.userId eq userId }) {
            it[NearbyUserAlertsTable.enabled] = enabled
            it[updatedAt] = Instant.now()
        }

        NearbyUserAlertsTable
            .selectAll()
            .where { NearbyUserAlertsTable.userId eq userId }
            .map(::rowToAlert)
            .singleOrNull()
    }

    override suspend fun deleteAlert(userId: String): Boolean = dbQuery {
        NearbyUserAlertsTable.deleteWhere { NearbyUserAlertsTable.userId eq userId } > 0
    }

    override suspend fun getEnabledAlerts(limit: Int): List<NearbyUserAlert> = dbQuery {
        NearbyUserAlertsTable
            .selectAll()
            .where { NearbyUserAlertsTable.enabled eq true }
            .limit(limit)
            .map(::rowToAlert)
    }

    override suspend fun markChecked(
        alertId: String,
        nearbyUserCount: Int,
        checkedAt: Instant
    ): NearbyUserAlert? = dbQuery {
        NearbyUserAlertsTable.update({ NearbyUserAlertsTable.id eq alertId }) {
            it[lastCheckedAt] = checkedAt
            it[lastNearbyUserCount] = nearbyUserCount
            it[updatedAt] = checkedAt
        }

        getAlertById(alertId)
    }

    override suspend fun markNotified(
        alertId: String,
        nearbyUserCount: Int,
        notifiedAt: Instant
    ): NearbyUserAlert? = dbQuery {
        NearbyUserAlertsTable.update({ NearbyUserAlertsTable.id eq alertId }) {
            it[lastCheckedAt] = notifiedAt
            it[lastNotifiedAt] = notifiedAt
            it[lastNearbyUserCount] = nearbyUserCount
            it[enabled] = false
            it[updatedAt] = notifiedAt
        }

        getAlertById(alertId)
    }

    private fun getAlertById(alertId: String): NearbyUserAlert? {
        return NearbyUserAlertsTable
            .selectAll()
            .where { NearbyUserAlertsTable.id eq alertId }
            .map(::rowToAlert)
            .singleOrNull()
    }

    private fun rowToAlert(row: ResultRow): NearbyUserAlert {
        return NearbyUserAlert(
            id = row[NearbyUserAlertsTable.id],
            userId = row[NearbyUserAlertsTable.userId],
            latitude = row[NearbyUserAlertsTable.latitude],
            longitude = row[NearbyUserAlertsTable.longitude],
            radiusMeters = row[NearbyUserAlertsTable.radiusMeters],
            minUserCount = row[NearbyUserAlertsTable.minUserCount],
            enabled = row[NearbyUserAlertsTable.enabled],
            lastCheckedAt = row[NearbyUserAlertsTable.lastCheckedAt],
            lastNotifiedAt = row[NearbyUserAlertsTable.lastNotifiedAt],
            lastNearbyUserCount = row[NearbyUserAlertsTable.lastNearbyUserCount],
            createdAt = row[NearbyUserAlertsTable.createdAt],
            updatedAt = row[NearbyUserAlertsTable.updatedAt]
        )
    }
}
