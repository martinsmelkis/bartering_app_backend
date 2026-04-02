package app.bartering.features.migration.di

import app.bartering.features.migration.dao.MigrationDao
import org.koin.dsl.module

/**
 * DI module for unified migration system (device-to-device and email recovery).
 */
val migrationModule = module {
    single { MigrationDao() }
}
