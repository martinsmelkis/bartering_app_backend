package app.bartering.features.nearbyalerts.service

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.nearbyalerts.dao.NearbyUserAlertsDao
import app.bartering.features.nearbyalerts.model.NearbyUserAlert
import app.bartering.features.nearbyalerts.model.UpsertNearbyUserAlertRequest
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.NotificationData
import app.bartering.features.notifications.model.UpdateUserNotificationContactsRequest
import app.bartering.features.notifications.service.NotificationOrchestrator
import app.bartering.features.profile.dao.UserProfileDao
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant

class NearbyUserAlertService(
    private val alertsDao: NearbyUserAlertsDao,
    private val profileDao: UserProfileDao,
    private val notificationOrchestrator: NotificationOrchestrator,
    private val notificationPreferencesDao: NotificationPreferencesDao
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun getAlertForUser(userId: String): NearbyUserAlert? {
        return alertsDao.getAlertForUser(userId)
    }

    suspend fun upsertAlert(userId: String, request: UpsertNearbyUserAlertRequest): NearbyUserAlert {
        require(isValidLatitude(request.latitude)) { "Latitude must be between -90 and 90" }
        require(isValidLongitude(request.longitude)) { "Longitude must be between -180 and 180" }
        require(request.radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            "Radius must be between ${MIN_RADIUS_METERS.toInt()} and ${MAX_RADIUS_METERS.toInt()} meters"
        }
        require(request.minUserCount in MIN_USER_THRESHOLD..MAX_USER_THRESHOLD) {
            "Minimum user count must be between $MIN_USER_THRESHOLD and $MAX_USER_THRESHOLD"
        }
        require(profileDao.hasLocationConsent(userId)) {
            "Location consent is required to create nearby user alerts"
        }

        val alert = alertsDao.upsertAlert(userId, request)
        if (request.enabled) {
            enableMarketingConsent(userId)
        }
        return alert
    }

    suspend fun setAlertEnabled(userId: String, enabled: Boolean): NearbyUserAlert? {
        if (enabled) {
            require(profileDao.hasLocationConsent(userId)) {
                "Location consent is required to enable nearby user alerts"
            }
        }
        return alertsDao.setAlertEnabled(userId, enabled)
    }

    suspend fun deleteAlert(userId: String): Boolean {
        return alertsDao.deleteAlert(userId)
    }

    private suspend fun enableMarketingConsent(userId: String) {
        notificationPreferencesDao.updateUserContacts(
            userId,
            UpdateUserNotificationContactsRequest(marketingConsent = true)
        )
    }

    suspend fun countNearbyUsers(alert: NearbyUserAlert): Int {
        return countNearbyUsers(
            userId = alert.userId,
            latitude = alert.latitude,
            longitude = alert.longitude,
            radiusMeters = alert.radiusMeters
        )
    }

    suspend fun countNearbyUsers(
        userId: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double
    ): Int = dbQuery {
        val sql = """
            SELECT COUNT(DISTINCT u.id) AS user_count
            FROM user_registration_data u
            INNER JOIN user_profiles up ON u.id = up.user_id
            INNER JOIN user_privacy_consents uc ON uc.user_id = u.id
            LEFT JOIN user_presence p ON p.user_id = u.id
            WHERE u.id != ?
                AND uc.location_consent = TRUE
                AND up.location IS NOT NULL
                AND ST_DWithin(
                    up.location::geography,
                    ST_MakePoint(?, ?)::geography,
                    ?
                )
                AND (
                    p.last_activity_at IS NULL
                    OR p.last_activity_at >= NOW() - INTERVAL '90 days'
                )
        """.trimIndent()

        (TransactionManager.current().connection.connection as Connection).prepareStatement(sql).use { statement ->
            statement.setString(1, userId)
            statement.setDouble(2, longitude)
            statement.setDouble(3, latitude)
            statement.setDouble(4, radiusMeters)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    (rs.getObject("user_count") as? Number)?.toInt() ?: 0
                } else {
                    0
                }
            }
        }
    }

    suspend fun processDueAlerts(limit: Int = 500): Int {
        val alerts = alertsDao.getEnabledAlerts(limit)
        var notifiedCount = 0

        for (alert in alerts) {
            try {
                if (!profileDao.hasLocationConsent(alert.userId)) {
                    alertsDao.setAlertEnabled(alert.userId, false)
                    log.info("Disabled nearby user alert {} because location consent was revoked", alert.id)
                    continue
                }

                if (!isDue(alert)) {
                    continue
                }

                val nearbyUserCount = countNearbyUsers(alert)
                if (nearbyUserCount >= alert.minUserCount) {
                    sendThresholdReachedNotification(alert, nearbyUserCount)
                    alertsDao.markNotified(alert.id, nearbyUserCount)
                    notifiedCount++
                } else {
                    alertsDao.markChecked(alert.id, nearbyUserCount)
                }
            } catch (e: Exception) {
                log.warn("Failed to process nearby user alert {} for user {}: {}", alert.id, alert.userId, e.message)
            }
        }

        return notifiedCount
    }

    private fun isDue(alert: NearbyUserAlert): Boolean {
        val lastCheckedAt = alert.lastCheckedAt ?: return true
        return Duration.between(lastCheckedAt, Instant.now()) >= CHECK_COOLDOWN
    }

    private suspend fun sendThresholdReachedNotification(alert: NearbyUserAlert, nearbyUserCount: Int) {
        val notification = NotificationData(
            title = "More people are nearby",
            body = "There are now $nearbyUserCount users near your selected area.",
            actionUrl = "/nearby",
            data = mapOf(
                "type" to "nearby_user_alert",
                "alertId" to alert.id,
                "nearbyUserCount" to nearbyUserCount.toString(),
                "radiusMeters" to alert.radiusMeters.toString(),
                "minUserCount" to alert.minUserCount.toString()
            )
        )

        notificationOrchestrator.sendNotification(alert.userId, notification)
    }

    private fun isValidLatitude(latitude: Double): Boolean = latitude in -90.0..90.0

    private fun isValidLongitude(longitude: Double): Boolean = longitude in -180.0..180.0

    companion object {
        private const val MIN_RADIUS_METERS = 100.0
        private const val MAX_RADIUS_METERS = 200_000.0
        private const val MIN_USER_THRESHOLD = 1
        private const val MAX_USER_THRESHOLD = 100
        private val CHECK_COOLDOWN: Duration = Duration.ofMinutes(30)
    }
}
