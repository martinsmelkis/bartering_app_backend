package app.bartering.features.nearbyalerts.di

import app.bartering.features.nearbyalerts.dao.NearbyUserAlertsDao
import app.bartering.features.nearbyalerts.dao.NearbyUserAlertsDaoImpl
import app.bartering.features.nearbyalerts.service.NearbyUserAlertService
import org.koin.dsl.module

val nearbyAlertsModule = module {
    single<NearbyUserAlertsDao> { NearbyUserAlertsDaoImpl() }
    single {
        NearbyUserAlertService(
            alertsDao = get(),
            profileDao = get(),
            notificationOrchestrator = get(),
            notificationPreferencesDao = get()
        )
    }
}
