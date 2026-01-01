package org.barter.features.healthcheck.routes

import org.barter.features.healthcheck.routes.gethealthcheck.getHealthCheck
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.healthCheckRoutes() {
    routing {
        getHealthCheck()
    }
}