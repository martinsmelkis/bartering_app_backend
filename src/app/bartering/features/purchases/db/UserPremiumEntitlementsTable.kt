package app.bartering.features.purchases.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object UserPremiumEntitlementsTable : Table("user_premium_entitlements") {
    val userId = reference("user_id", UserRegistrationDataTable.id, onDelete = ReferenceOption.CASCADE)
    val isPremium = bool("is_premium").default(false)
    val isLifetime = bool("is_lifetime").default(false)
    val grantedByPurchaseId = reference("granted_by_purchase_id", UserPurchasesTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val grantedAt = timestamp("granted_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}
