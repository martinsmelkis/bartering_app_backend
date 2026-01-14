package app.bartering.features.healthcheck.routes

import app.bartering.features.healthcheck.routes.gethealthcheck.getHealthCheck
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.healthCheckRoutes() {
    routing {
        getHealthCheck()
    }
}