package app.bartering

import app.bartering.features.healthcheck.routes.healthCheckRoutes
import io.ktor.server.application.*
import app.bartering.features.ai.routes.userAttributePreferencesRoutes
import app.bartering.features.authentication.routes.authenticationRoutes
import app.bartering.features.analytics.routes.getDailyActivityStatsRoute
import app.bartering.features.authentication.routes.deviceManagementRoutes
import app.bartering.features.chat.manager.ConnectionManager
import app.bartering.features.chat.routes.chatRoutes
import app.bartering.features.encryptedfiles.routes.fileTransferRoutes
import app.bartering.features.migration.routes.migrationRoutes
import app.bartering.features.postings.routes.imageServeRoutes
import app.bartering.features.postings.routes.postingImageUploadRoutes
import app.bartering.features.postings.routes.postingsRoutes
import app.bartering.features.profile.routes.profileManagementRoutes
import app.bartering.features.profile.routes.*
import app.bartering.features.relationships.routes.relationshipsRoutes
import app.bartering.features.notifications.routes.notificationPreferencesRoutes
import app.bartering.features.notifications.routes.pushNotificationRoutes
import app.bartering.features.reviews.routes.*
import app.bartering.features.federation.routes.federationRoutes
import app.bartering.features.federation.routes.federationAdminRoutes
import app.bartering.features.wallet.routes.*
import app.bartering.features.purchases.routes.*
import app.bartering.features.compliance.routes.complianceRoutes
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.*
import org.koin.java.KoinJavaComponent.inject

fun Application.routes() {
    // Get ConnectionManager from Koin DI (shared instance for WebSocket and file transfer routes)
    val connectionManager: ConnectionManager by inject(ConnectionManager::class.java)
    
    authenticationRoutes()
    deviceManagementRoutes()
    migrationRoutes()
    profileManagementRoutes()
    userAttributePreferencesRoutes()
    chatRoutes(connectionManager)
    fileTransferRoutes(connectionManager)
    notificationPreferencesRoutes()
    pushNotificationRoutes()
    healthCheckRoutes()
    complianceRoutes()
    routing {
        federationRoutes()
        federationAdminRoutes()
        relationshipsRoutes()

        // Posting routes (specific endpoint limits are applied inside route definitions)
        postingsRoutes()

        // Multipart/media uploads
        rateLimit(RateLimitName("file_upload")) {
            postingImageUploadRoutes()
        }

        // Serves local images
        imageServeRoutes()

        // User Presence/Online Status
        getUserOnlineStatusRoute()
        getDailyActivityStatsRoute()
        batchUserOnlineStatusRoute()
        getPresenceCacheStatsRoute()

        // Reviews and Reputation System
        createTransactionRoute()
        updateTransactionStatusRoute()
        getUserTransactionsRoute()
        getTransactionWithPartnerRoute()
        checkReviewEligibilityRoute()
        submitReviewRoute()
        submitReviewAppealRoute()
        getUserAppealsRoute()
        getReviewAppealsModerationRoute()
        deleteReviewModerationRoute()
        getUserReviewsRoute()
        getTransactionReviewsRoute()
        getReputationRoute()
        getUserBadgesRoute()

        // Wallet / Barter Coins
        getWalletRoute()
        getWalletTransactionsRoute()
        transferCoinsRoute()
        claimWalletAwardRoute()

        // Purchases (premium + coin packs + boosts + avatar icon unlocks)
        getPremiumStatusRoute()
        getPurchaseHistoryRoute()
        getAvatarIconOwnershipStatusRoute()
        purchasePremiumLifetimeRoute()
        purchaseCoinPackRoute()
        purchaseVisibilityBoostRoute()
        rateLimit(RateLimitName("wallet_purchase")) {
            purchaseAvatarIconRoute()
            equipAvatarIconRoute()
        }

        // RevenueCat premium mirror sync
        revenueCatWebhookRoute()
        syncPremiumNowRoute()
    }
}