package app.bartering.features.federation.di

import app.bartering.features.federation.dao.FederationDao
import app.bartering.features.federation.dao.FederationDaoImpl
import app.bartering.features.federation.dao.FederatedUserDao
import app.bartering.features.federation.dao.FederatedUserDaoImpl
import app.bartering.features.federation.service.FederationService
import app.bartering.features.federation.service.FederationServiceImpl
import org.koin.dsl.module

/**
 * Koin dependency injection module for federation feature.
 */
val federationModule = module {

    // DAO layer
    single<FederationDao> { FederationDaoImpl() }
    single<FederatedUserDao> { FederatedUserDaoImpl(get()) }

    // Service layer
    single<FederationService> {
        FederationServiceImpl(
            dao = get()
        )
    }
}