package app.bartering.features.nearbyalerts.dao

import app.bartering.features.nearbyalerts.model.NearbyUserAlert
import app.bartering.features.nearbyalerts.model.UpsertNearbyUserAlertRequest
import java.time.Instant

interface NearbyUserAlertsDao {
    suspend fun getAlertForUser(userId: String): NearbyUserAlert?
    suspend fun upsertAlert(userId: String, request: UpsertNearbyUserAlertRequest): NearbyUserAlert
    suspend fun setAlertEnabled(userId: String, enabled: Boolean): NearbyUserAlert?
    suspend fun deleteAlert(userId: String): Boolean
    suspend fun getEnabledAlerts(limit: Int = 500): List<NearbyUserAlert>
    suspend fun markChecked(alertId: String, nearbyUserCount: Int, checkedAt: Instant = Instant.now()): NearbyUserAlert?
    suspend fun markNotified(alertId: String, nearbyUserCount: Int, notifiedAt: Instant = Instant.now()): NearbyUserAlert?
}
