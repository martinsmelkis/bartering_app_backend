package app.bartering.features.healthcheck.di

import app.bartering.features.healthcheck.data.HealthCheckDataImpl
import app.bartering.features.healthcheck.data.HealthCheckData
import org.koin.dsl.bind
import org.koin.dsl.module

val healthCheckModule = module {
    single { HealthCheckDataImpl() } bind HealthCheckData::class
}