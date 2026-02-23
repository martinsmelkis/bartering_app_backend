package app.bartering

import app.bartering.features.healthcheck.routes.healthCheckRoutes
import io.ktor.server.application.*
import app.bartering.features.ai.routes.userAttributePreferencesRoutes
import app.bartering.features.authentication.routes.authenticationRoutes
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
import io.ktor.server.routing.*
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
    routing {
        federationRoutes()
        federationAdminRoutes()
        relationshipsRoutes()
        postingsRoutes()
        postingImageUploadRoutes()
        imageServeRoutes() // Serves local images
        
        // User Presence/Online Status
        getUserOnlineStatusRoute()
        batchUserOnlineStatusRoute()
        getPresenceCacheStatsRoute()
        
        // Reviews and Reputation System
        createTransactionRoute()
        updateTransactionStatusRoute()
        getUserTransactionsRoute()
        getTransactionWithPartnerRoute()
        checkReviewEligibilityRoute()
        submitReviewRoute()
        getUserReviewsRoute()
        getTransactionReviewsRoute()
        getReputationRoute()
        getUserBadgesRoute()
    }
}