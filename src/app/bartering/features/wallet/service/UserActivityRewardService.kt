package app.bartering.features.wallet.service

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.profile.db.UserPresenceTable
import app.bartering.features.wallet.db.LedgerTransactionsTable
import app.bartering.features.wallet.db.UserActivityRewardProgressTable
import app.bartering.features.wallet.model.TransactionType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class UserActivityRewardService(
    private val walletService: WalletService
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val REWARD_ACTIONS_STEP = 10L
        private const val REWARD_COINS_PER_STEP = 10L
        private const val DAILY_REWARD_CAP_COINS = 20L
    }

    suspend fun processRewardsForAllUsers(): Int = dbQuery {
        val users: List<Pair<String, Long>> = UserPresenceTable
            .selectAll()
            .map { row ->
                row[UserPresenceTable.userId] to row[UserPresenceTable.totalActivityCount]
            }

        var rewardedUsers = 0
        users.forEach { (userId, totalActivityCount) ->
            if (rewardUserIfEligible(userId, totalActivityCount)) {
                rewardedUsers++
            }
        }

        rewardedUsers
    }

    private suspend fun rewardUserIfEligible(userId: String, totalActivityCount: Long): Boolean {
        val rewardedCountBefore = getOrCreateRewardedActivityCount(userId)
        val reachedMilestoneCount = (totalActivityCount / REWARD_ACTIONS_STEP) * REWARD_ACTIONS_STEP

        if (reachedMilestoneCount <= rewardedCountBefore) {
            return false
        }

        val newlyRewardableActions = reachedMilestoneCount - rewardedCountBefore
        val stepsToReward = newlyRewardableActions / REWARD_ACTIONS_STEP
        val maxCoinsByMilestones = stepsToReward * REWARD_COINS_PER_STEP

        if (maxCoinsByMilestones <= 0L) {
            return false
        }

        val rewardedTodayCoins = getRewardedTodayCoins(userId)
        val remainingTodayCap = (DAILY_REWARD_CAP_COINS - rewardedTodayCoins).coerceAtLeast(0L)
        val coinsToReward = minOf(maxCoinsByMilestones, remainingTodayCap)

        if (coinsToReward <= 0L) {
            return false
        }

        val rewardedActionsIncrement = (coinsToReward / REWARD_COINS_PER_STEP) * REWARD_ACTIONS_STEP
        val newRewardedCount = rewardedCountBefore + rewardedActionsIncrement

        val externalRef = "activity-count:$userId:$newRewardedCount"
        val metadataJson = "{\"reason\":\"activity_count_reward\",\"totalActivityCount\":$totalActivityCount,\"rewardedUpTo\":$newRewardedCount,\"dailyCap\":$DAILY_REWARD_CAP_COINS}"

        val rewardSucceeded = walletService.earnCoins(
            userId = userId,
            amount = coinsToReward,
            transactionType = TransactionType.BONUS,
            externalRef = externalRef,
            metadataJson = metadataJson
        )

        if (!rewardSucceeded) {
            log.warn("Failed to reward activity-count bonus for userId={} (coins={})", userId, coinsToReward)
            return false
        }

        setRewardedActivityCount(userId, newRewardedCount)
        log.info(
            "Awarded activity-count reward: userId={}, totalActivityCount={}, rewardedUpTo={}, coins={}, dailyCap={}",
            userId,
            totalActivityCount,
            newRewardedCount,
            coinsToReward,
            DAILY_REWARD_CAP_COINS
        )
        return true
    }

    private suspend fun getOrCreateRewardedActivityCount(userId: String): Long = dbQuery {
        val existing = UserActivityRewardProgressTable
            .selectAll()
            .where { UserActivityRewardProgressTable.userId eq userId }
            .singleOrNull()

        if (existing != null) {
            return@dbQuery existing[UserActivityRewardProgressTable.rewardedActivityCount]
        }

        UserActivityRewardProgressTable.insertIgnore {
            it[UserActivityRewardProgressTable.userId] = userId
            it[rewardedActivityCount] = 0L
            it[updatedAt] = Instant.now()
        }

        0L
    }

    private suspend fun setRewardedActivityCount(userId: String, rewardedCount: Long): Boolean = dbQuery {
        UserActivityRewardProgressTable.update({ UserActivityRewardProgressTable.userId eq userId }) {
            it[rewardedActivityCount] = rewardedCount
            it[updatedAt] = Instant.now()
        } > 0
    }

    private suspend fun getRewardedTodayCoins(userId: String): Long = dbQuery {
        val dayStartUtc = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)

        LedgerTransactionsTable
            .selectAll()
            .where {
                (LedgerTransactionsTable.toUserId eq userId) and
                    (LedgerTransactionsTable.type eq TransactionType.BONUS.value) and
                    (LedgerTransactionsTable.createdAt greaterEq dayStartUtc)
            }
            .filter { row ->
                row[LedgerTransactionsTable.externalRef]?.startsWith("activity-count:$userId:") == true
            }
            .sumOf { row -> row[LedgerTransactionsTable.amount] }
    }
}
