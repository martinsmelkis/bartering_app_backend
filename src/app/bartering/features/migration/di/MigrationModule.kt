package app.bartering.features.migration.di

import app.bartering.features.migration.dao.MigrationSessionDao
import org.koin.dsl.module

/**
 * Dependency injection module for the migration feature.
 */
val migrationModule = module {
    single { MigrationSessionDao() }
}
