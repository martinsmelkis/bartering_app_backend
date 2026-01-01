package org.barter.features.healthcheck.routes.gethealthcheck

import org.barter.features.healthcheck.data.HealthCheckData
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.java.KoinJavaComponent.inject

fun Route.getHealthCheck() {
    val service: HealthCheckData by inject(HealthCheckData::class.java)
    get("/public-api/v1/healthCheck") {
        call.respond(object {
            val response = service.getHealthCheckStatus()
        })
    }
}