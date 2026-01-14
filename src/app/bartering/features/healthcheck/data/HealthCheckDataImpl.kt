package app.bartering.features.healthcheck.data

class HealthCheckDataImpl : HealthCheckData {
    override fun getHealthCheckStatus(): String {
        return "Healthy"
    }

}