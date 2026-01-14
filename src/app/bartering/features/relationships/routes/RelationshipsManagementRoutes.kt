package app.bartering.features.relationships.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.relationshipsRoutes() {
    routing {
        // Relationship management
        createRelationshipRoute()
        removeRelationshipRoute()
        getUserRelationshipsRoute()
        getRelationshipsWithProfilesRoute()
        acceptFriendRequestRoute()
        rejectFriendRequestRoute()
        getRelationshipStatsRoute()
        checkRelationshipRoute()
        getFavoritedProfilesRoute()
        
        // User blocking
        blockUserRoute()
        unblockUserRoute()
        checkIsBlockedRoute()
        getBlockedUsersRoute()
        getBlockedByUsersRoute()
        
        // User reporting
        createUserReportRoute()
        getUserReportsRoute()
        checkHasReportedRoute()
        getUserReportStatsRoute()
    }
}
