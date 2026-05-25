package app.bartering.features.nearbyalerts.tasks

import app.bartering.features.nearbyalerts.service.NearbyUserAlertService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class NearbyUserAlertTask(
    private val alertService: NearbyUserAlertService,
    private val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("Starting NearbyUserAlertTask (runs every {} minutes)", intervalMinutes)
            while (true) {
                try {
                    val notifiedCount = alertService.processDueAlerts()
                    if (notifiedCount > 0) {
                        log.info("NearbyUserAlertTask sent {} alert notification(s)", notifiedCount)
                    } else {
                        log.debug("NearbyUserAlertTask complete: no alerts triggered")
                    }
                } catch (e: Exception) {
                    log.error("Error while processing nearby user alerts", e)
                }

                delay(intervalMinutes * 60 * 1000)
            }
        }
    }

    companion object {
        private val DEFAULT_INTERVAL_MINUTES: Long =
            System.getenv("NEARBY_USER_ALERT_INTERVAL_MINUTES")?.toLongOrNull()?.coerceAtLeast(5) ?: 30
    }
}
