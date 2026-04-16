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
    val rcCustomerId = varchar("rc_customer_id", 128).nullable()
    val rcAppUserId = varchar("rc_app_user_id", 128).nullable()
    val rcEntitlementId = varchar("rc_entitlement_id", 128).nullable()
    val rcLastEventId = varchar("rc_last_event_id", 128).nullable()
    val rcLastEventType = varchar("rc_last_event_type", 64).nullable()
    val lastEventAt = timestamp("last_event_at").nullable()
    val entitlementSource = varchar("source", 32).nullable()

    override val primaryKey = PrimaryKey(userId)
}
