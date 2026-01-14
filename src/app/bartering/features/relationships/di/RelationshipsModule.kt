package app.bartering.features.relationships.di

import app.bartering.features.relationships.dao.UserRelationshipsDao
import app.bartering.features.relationships.dao.UserRelationshipsDaoImpl
import app.bartering.features.relationships.dao.UserReportsDao
import app.bartering.features.relationships.dao.UserReportsDaoImpl
import org.koin.dsl.module

val relationshipsModule = module {
    single<UserRelationshipsDao> { UserRelationshipsDaoImpl() }
    single { UserRelationshipsDaoImpl() }
    single<UserReportsDao> { UserReportsDaoImpl() }
    single { UserReportsDaoImpl() }
}
