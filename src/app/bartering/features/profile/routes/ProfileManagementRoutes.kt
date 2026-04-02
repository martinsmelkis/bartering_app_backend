package app.bartering.features.profile.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.*

fun Application.profileManagementRoutes() {
    routing {
        // Expensive profile discovery/search operations
        rateLimit(RateLimitName("expensive_query")) {
            getProfilesNearbyRoute()
            searchProfilesByKeywordRoute()
            similarProfilesRoute()
            complementaryProfilesRoute()
        }

        // Profile mutation operations
        rateLimit(RateLimitName("profile_update")) {
            createProfileRoute()
            updateProfileRoute()
            updateUserConsentRoute()
            requestGdprDataExportRoute()
        }

        // Profile reads
        getProfileInfoRoute()
        getExtendedProfileInfoRoute()
    }
}