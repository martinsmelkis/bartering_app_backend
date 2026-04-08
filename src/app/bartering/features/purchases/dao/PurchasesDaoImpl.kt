package app.bartering.features.purchases.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.purchases.db.UserPremiumEntitlementsTable
import app.bartering.features.purchases.db.UserPurchasesTable
import app.bartering.features.purchases.model.PremiumEntitlement
import app.bartering.features.purchases.model.PurchaseStatus
import app.bartering.features.purchases.model.PurchaseType
import app.bartering.features.purchases.model.UserPurchase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class PurchasesDaoImpl : PurchasesDao {
    override suspend fun createPurchase(purchase: UserPurchase): Boolean = dbQuery {
        UserPurchasesTable.insert {
            it[id] = purchase.id
            it[userId] = purchase.userId
            it[purchaseType] = purchase.purchaseType.value
            it[status] = purchase.status.value
            it[currency] = purchase.currency
            it[fiatAmountMinor] = purchase.fiatAmountMinor
            it[coinAmount] = purchase.coinAmount
            it[externalRef] = purchase.externalRef
            it[metadataJson] = purchase.metadataJson
            it[fulfillmentRef] = purchase.fulfillmentRef
            it[createdAt] = purchase.createdAt
            it[updatedAt] = purchase.updatedAt
        }
        true
    }

    override suspend fun updatePurchaseStatus(
        purchaseId: String,
        status: PurchaseStatus,
        fulfillmentRef: String?
    ): Boolean = dbQuery {
        UserPurchasesTable.update({ UserPurchasesTable.id eq purchaseId }) {
            it[UserPurchasesTable.status] = status.value
            it[UserPurchasesTable.fulfillmentRef] = fulfillmentRef
            it[updatedAt] = Instant.now()
        } > 0
    }

    override suspend fun getPurchasesForUser(userId: String, limit: Int, offset: Long): List<UserPurchase> = dbQuery {
        UserPurchasesTable
            .selectAll()
            .where { UserPurchasesTable.userId eq userId }
            .orderBy(UserPurchasesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map(::rowToUserPurchase)
    }

    override suspend fun getPremiumEntitlement(userId: String): PremiumEntitlement = dbQuery {
        UserPremiumEntitlementsTable
            .selectAll()
            .where { UserPremiumEntitlementsTable.userId eq userId }
            .map(::rowToPremiumEntitlement)
            .singleOrNull()
            ?: PremiumEntitlement(
                userId = userId,
                isPremium = false,
                isLifetime = false,
                grantedByPurchaseId = null,
                grantedAt = null,
                expiresAt = null,
                updatedAt = Instant.now()
            )
    }

    override suspend fun upsertPremiumEntitlement(entitlement: PremiumEntitlement): Boolean = dbQuery {
        val updated = UserPremiumEntitlementsTable.update({ UserPremiumEntitlementsTable.userId eq entitlement.userId }) {
            it[isPremium] = entitlement.isPremium
            it[isLifetime] = entitlement.isLifetime
            it[grantedByPurchaseId] = entitlement.grantedByPurchaseId
            it[grantedAt] = entitlement.grantedAt
            it[expiresAt] = entitlement.expiresAt
            it[updatedAt] = entitlement.updatedAt
        }

        if (updated > 0) {
            true
        } else {
            UserPremiumEntitlementsTable.insertIgnore {
                it[userId] = entitlement.userId
                it[isPremium] = entitlement.isPremium
                it[isLifetime] = entitlement.isLifetime
                it[grantedByPurchaseId] = entitlement.grantedByPurchaseId
                it[grantedAt] = entitlement.grantedAt
                it[expiresAt] = entitlement.expiresAt
                it[updatedAt] = entitlement.updatedAt
            }
            true
        }
    }

    private fun rowToUserPurchase(row: ResultRow): UserPurchase {
        return UserPurchase(
            id = row[UserPurchasesTable.id],
            userId = row[UserPurchasesTable.userId],
            purchaseType = PurchaseType.fromString(row[UserPurchasesTable.purchaseType]) ?: PurchaseType.COIN_PACK,
            status = PurchaseStatus.fromString(row[UserPurchasesTable.status]) ?: PurchaseStatus.PENDING,
            currency = row[UserPurchasesTable.currency],
            fiatAmountMinor = row[UserPurchasesTable.fiatAmountMinor],
            coinAmount = row[UserPurchasesTable.coinAmount],
            externalRef = row[UserPurchasesTable.externalRef],
            metadataJson = row[UserPurchasesTable.metadataJson],
            fulfillmentRef = row[UserPurchasesTable.fulfillmentRef],
            createdAt = row[UserPurchasesTable.createdAt],
            updatedAt = row[UserPurchasesTable.updatedAt]
        )
    }

    private fun rowToPremiumEntitlement(row: ResultRow): PremiumEntitlement {
        return PremiumEntitlement(
            userId = row[UserPremiumEntitlementsTable.userId],
            isPremium = row[UserPremiumEntitlementsTable.isPremium],
            isLifetime = row[UserPremiumEntitlementsTable.isLifetime],
            grantedByPurchaseId = row[UserPremiumEntitlementsTable.grantedByPurchaseId],
            grantedAt = row[UserPremiumEntitlementsTable.grantedAt],
            expiresAt = row[UserPremiumEntitlementsTable.expiresAt],
            updatedAt = row[UserPremiumEntitlementsTable.updatedAt]
        )
    }
}
