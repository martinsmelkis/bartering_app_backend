package app.bartering.features.profile.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.profileManagementRoutes() {
    routing {
        getProfileInfoRoute()
        createProfileRoute()
        updateProfileRoute()
        getProfilesNearbyRoute()
        searchProfilesByKeywordRoute()
        similarProfilesRoute()
        complementaryProfilesRoute()
    }
}