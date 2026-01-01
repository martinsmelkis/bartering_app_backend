package org.barter.features.healthcheck.data

class HealthCheckDataImpl : HealthCheckData {
    override fun getHealthCheckStatus(): String {
        return "Healthy"
    }

}