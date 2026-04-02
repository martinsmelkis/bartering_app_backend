package app.bartering.features.ai.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.*

fun Application.userAttributePreferencesRoutes() {
    routing {
        rateLimit(RateLimitName("expensive_query")) {
            getInterestsFromOnboardingData()
            getOfferingsFromInterestsData()
            parseOfferingsAndUpdateProfile()
        }
    }
}