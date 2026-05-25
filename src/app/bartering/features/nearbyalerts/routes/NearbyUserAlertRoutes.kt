package app.bartering.features.nearbyalerts.routes

import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.nearbyalerts.model.NearbyUserAlertOperationResponse
import app.bartering.features.nearbyalerts.model.NearbyUserAlertResponse
import app.bartering.features.nearbyalerts.model.UpsertNearbyUserAlertRequest
import app.bartering.features.nearbyalerts.service.NearbyUserAlertService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject

@Serializable
private data class SetNearbyUserAlertEnabledRequest(
    val enabled: Boolean
)

fun Application.nearbyUserAlertRoutes() {
    val alertService: NearbyUserAlertService by inject(NearbyUserAlertService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    routing {
        rateLimit(RateLimitName("notification_prefs")) {
            route("/api/v1/notifications/nearby-users-alert") {
                get {
                    val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                    if (authenticatedUserId == null) return@get

                    val alert = alertService.getAlertForUser(authenticatedUserId)
                    val currentCount = if (alert != null) alertService.countNearbyUsers(alert) else null
                    call.respond(HttpStatusCode.OK, NearbyUserAlertResponse(alert, currentCount))
                }

                post {
                    val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                    if (authenticatedUserId == null || requestBody == null) return@post

                    try {
                        val request = Json.decodeFromString<UpsertNearbyUserAlertRequest>(requestBody)
                        val alert = alertService.upsertAlert(authenticatedUserId, request)
                        val currentCount = alertService.countNearbyUsers(alert)
                        call.respond(
                            HttpStatusCode.OK,
                            NearbyUserAlertResponse(
                                alert = alert,
                                currentNearbyUserCount = currentCount
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            NearbyUserAlertOperationResponse(false, e.message ?: "Invalid nearby user alert request")
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            NearbyUserAlertOperationResponse(false, "Invalid request format: ${e.message}")
                        )
                    }
                }

                patch {
                    val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
                    if (authenticatedUserId == null || requestBody == null) return@patch

                    try {
                        val request = Json.decodeFromString<SetNearbyUserAlertEnabledRequest>(requestBody)
                        val alert = alertService.setAlertEnabled(authenticatedUserId, request.enabled)
                        if (alert == null) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                NearbyUserAlertOperationResponse(false, "Nearby user alert not found")
                            )
                            return@patch
                        }

                        call.respond(
                            HttpStatusCode.OK,
                            NearbyUserAlertOperationResponse(true, "Nearby user alert updated", alert)
                        )
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            NearbyUserAlertOperationResponse(false, e.message ?: "Invalid nearby user alert request")
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            NearbyUserAlertOperationResponse(false, "Invalid request format: ${e.message}")
                        )
                    }
                }

                delete {
                    val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
                    if (authenticatedUserId == null) return@delete

                    val deleted = alertService.deleteAlert(authenticatedUserId)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, NearbyUserAlertOperationResponse(true, "Nearby user alert deleted"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, NearbyUserAlertOperationResponse(false, "Nearby user alert not found"))
                    }
                }
            }
        }
    }
}
