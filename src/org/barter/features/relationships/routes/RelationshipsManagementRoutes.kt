package org.barter.features.relationships.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.relationshipsRoutes() {
    routing {
        createRelationshipRoute()
        removeRelationshipRoute()
        getUserRelationshipsRoute()
        getRelationshipsWithProfilesRoute()
        acceptFriendRequestRoute()
        rejectFriendRequestRoute()
        getRelationshipStatsRoute()
        checkRelationshipRoute()
        getFavoritedProfilesRoute()
    }
}
