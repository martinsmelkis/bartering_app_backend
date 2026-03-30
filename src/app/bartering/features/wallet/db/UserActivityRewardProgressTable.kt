package app.bartering.features.wallet.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object UserActivityRewardProgressTable : Table("user_activity_reward_progress") {
    val userId = reference("user_id", UserRegistrationDataTable.id)
    val rewardedActivityCount = long("rewarded_activity_count").default(0)
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}
