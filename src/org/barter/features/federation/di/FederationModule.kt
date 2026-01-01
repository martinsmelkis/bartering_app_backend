package org.barter.features.federation.di

import org.barter.features.federation.dao.FederationDao
import org.barter.features.federation.dao.FederationDaoImpl
import org.barter.features.federation.service.FederationService
import org.barter.features.federation.service.FederationServiceImpl
import org.koin.dsl.module

/**
 * Koin dependency injection module for federation feature.
 */
val federationModule = module {

    // DAO layer
    single<FederationDao> { FederationDaoImpl() }

    // Service layer
    single<FederationService> {
        FederationServiceImpl(
            dao = get()
        )
    }
}