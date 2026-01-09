package org.barter

import org.barter.features.healthcheck.routes.healthCheckRoutes
import io.ktor.server.application.*
import org.barter.features.ai.routes.userAttributePreferencesRoutes
import org.barter.features.authentication.routes.authenticationRoutes
import org.barter.features.chat.manager.ConnectionManager
import org.barter.features.chat.routes.chatRoutes
import org.barter.features.encryptedfiles.routes.fileTransferRoutes
import org.barter.features.postings.routes.imageServeRoutes
import org.barter.features.postings.routes.postingImageUploadRoutes
import org.barter.features.postings.routes.postingsRoutes
import org.barter.features.profile.routes.profileManagementRoutes
import org.barter.features.profile.routes.*
import org.barter.features.relationships.routes.relationshipsRoutes
import org.barter.features.notifications.routes.notificationPreferencesRoutes
import org.barter.features.notifications.routes.pushNotificationRoutes
import org.barter.features.reviews.routes.*
import io.ktor.server.routing.*

// Shared ConnectionManager instance for both WebSocket and file transfer routes
private val sharedConnectionManager = ConnectionManager()

fun Application.routes() {
    authenticationRoutes()
    profileManagementRoutes()
    userAttributePreferencesRoutes()
    chatRoutes(sharedConnectionManager)
    fileTransferRoutes(sharedConnectionManager)
    notificationPreferencesRoutes()
    pushNotificationRoutes()
    healthCheckRoutes()
    routing {
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