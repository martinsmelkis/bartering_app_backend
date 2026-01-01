package org.barter.features.healthcheck.di

import org.barter.features.healthcheck.data.HealthCheckDataImpl
import org.barter.features.healthcheck.data.HealthCheckData
import org.koin.dsl.bind
import org.koin.dsl.module

val healthCheckModule = module {
    single { HealthCheckDataImpl() } bind HealthCheckData::class
}