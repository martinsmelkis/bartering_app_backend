package app.bartering.features.analytics.routes

import app.bartering.features.analytics.model.UserDailyActivityStatsResponse
import app.bartering.features.analytics.service.UserDailyActivityStatsService
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.java.KoinJavaComponent.inject

fun Route.getDailyActivityStatsRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val statsService: UserDailyActivityStatsService by inject(UserDailyActivityStatsService::class.java)

    get("/api/v1/analytics/daily-stats") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@get
        }

        val days = call.request.queryParameters["days"]?.toLongOrNull() ?: 30L
        if (days !in 1L..365L) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "days must be between 1 and 365")
            )
            return@get
        }

        val stats = statsService.getUserStats(authenticatedUserId, days)
        call.respond(HttpStatusCode.OK, UserDailyActivityStatsResponse(success = true, stats = stats))
    }
}
