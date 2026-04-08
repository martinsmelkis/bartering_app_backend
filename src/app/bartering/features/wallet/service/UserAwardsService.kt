package app.bartering.features.wallet.service

import app.bartering.features.wallet.dao.WalletDao
import app.bartering.features.wallet.model.TransactionType
import org.slf4j.LoggerFactory
import java.util.Locale

class UserAwardsService(
    private val walletService: WalletService,
    private val walletDao: WalletDao
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private data class AwardDefinition(
        val type: String,
        val amount: Long,
        val requiresZeroWalletBalance: Boolean,
        val oneTimePerUser: Boolean
    )

    private val awardDefinitions = mapOf(
        "referral_signup" to AwardDefinition(
            type = "referral_signup",
            amount = 50L,
            requiresZeroWalletBalance = true,
            oneTimePerUser = true
        )
    )

    data class ClaimResult(
        val success: Boolean,
        val awarded: Boolean,
        val awardType: String,
        val amount: Long,
        val externalRef: String,
        val message: String
    )

    suspend fun claimAward(
        requesterUserId: String,
        targetUserId: String,
        awardType: String,
        externalRef: String?,
        metadataJson: String?
    ): ClaimResult {
        val normalizedType = awardType.trim().lowercase(Locale.ROOT)
        val definition = awardDefinitions[normalizedType]
            ?: return ClaimResult(
                success = false,
                awarded = false,
                awardType = normalizedType,
                amount = 0,
                externalRef = externalRef ?: "",
                message = "Unsupported awardType: $awardType"
            )

        if (requesterUserId != targetUserId) {
            return ClaimResult(
                success = false,
                awarded = false,
                awardType = normalizedType,
                amount = definition.amount,
                externalRef = externalRef ?: "",
                message = "You can only claim awards for your own account"
            )
        }

        val canonicalAwardRefPrefix = "award:${normalizedType}:${targetUserId}:"
        if (definition.oneTimePerUser) {
            val alreadyReceivedThisAwardType = walletDao.hasReceivedBonusWithExternalRefPrefix(
                userId = targetUserId,
                externalRefPrefix = canonicalAwardRefPrefix
            )
            if (alreadyReceivedThisAwardType) {
                return ClaimResult(
                    success = true,
                    awarded = false,
                    awardType = normalizedType,
                    amount = definition.amount,
                    externalRef = "award:${normalizedType}:${targetUserId}:already-claimed",
                    message = "Award already claimed"
                )
            }
        }

        val clientRefPart = externalRef
            ?.trim()
            ?.replace(Regex("[^A-Za-z0-9:_\\-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "default"

        val resolvedExternalRef = "$canonicalAwardRefPrefix$clientRefPart"

        val alreadyAwardedByRef = walletDao.existsTransactionByExternalRef(resolvedExternalRef)
        if (alreadyAwardedByRef) {
            return ClaimResult(
                success = true,
                awarded = false,
                awardType = normalizedType,
                amount = definition.amount,
                externalRef = resolvedExternalRef,
                message = "Award already claimed"
            )
        }

        if (definition.requiresZeroWalletBalance) {
            val wallet = walletService.getWallet(targetUserId)
            if (wallet.availableBalance != 0L) {
                return ClaimResult(
                    success = true,
                    awarded = false,
                    awardType = normalizedType,
                    amount = definition.amount,
                    externalRef = resolvedExternalRef,
                    message = "Award is only available when wallet balance is 0"
                )
            }
        }

        val defaultMetadata = "{\"reason\":\"user_award\",\"awardType\":\"$normalizedType\",\"targetUserId\":\"$targetUserId\"}"
        val rewardMetadata = metadataJson ?: defaultMetadata

        val credited = walletService.earnCoins(
            userId = targetUserId,
            amount = definition.amount,
            transactionType = TransactionType.BONUS,
            externalRef = resolvedExternalRef,
            metadataJson = rewardMetadata
        )

        if (!credited) {
            log.warn("Failed to credit award type={} userId={} externalRef={}", normalizedType, targetUserId, resolvedExternalRef)
            return ClaimResult(
                success = false,
                awarded = false,
                awardType = normalizedType,
                amount = definition.amount,
                externalRef = resolvedExternalRef,
                message = "Failed to apply award"
            )
        }

        return ClaimResult(
            success = true,
            awarded = true,
            awardType = normalizedType,
            amount = definition.amount,
            externalRef = resolvedExternalRef,
            message = "Award credited"
        )
    }
}
