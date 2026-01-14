package app.bartering.features.ai.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.userAttributePreferencesRoutes() {
    routing {
        getInterestsFromOnboardingData()
        getOfferingsFromInterestsData()
        parseOfferingsAndUpdateProfile()
    }
}