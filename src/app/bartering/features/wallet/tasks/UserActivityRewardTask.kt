package app.bartering.features.wallet.tasks

import app.bartering.features.wallet.service.UserActivityRewardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class UserActivityRewardTask(
    private val rewardService: UserActivityRewardService,
    private val intervalHours: Long = 24
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("Starting OnlineDaysRewardTask (activity-count rewards, runs every {}h)", intervalHours)
            while (true) {
                try {
                    val rewardedUsers = rewardService.processRewardsForAllUsers()
                    if (rewardedUsers > 0) {
                        log.info("OnlineDaysRewardTask complete: rewarded {} users", rewardedUsers)
                    } else {
                        log.debug("OnlineDaysRewardTask complete: no eligible users")
                    }
                } catch (e: Exception) {
                    log.error("Error while processing User activity rewards", e)
                }

                delay(intervalHours * 60 * 60 * 1000)
            }
        }
    }
}
