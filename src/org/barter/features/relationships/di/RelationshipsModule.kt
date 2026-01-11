package org.barter.features.relationships.di

import org.barter.features.relationships.dao.UserRelationshipsDao
import org.barter.features.relationships.dao.UserRelationshipsDaoImpl
import org.barter.features.relationships.dao.UserReportsDao
import org.barter.features.relationships.dao.UserReportsDaoImpl
import org.koin.dsl.module

val relationshipsModule = module {
    single<UserRelationshipsDao> { UserRelationshipsDaoImpl() }
    single { UserRelationshipsDaoImpl() }
    single<UserReportsDao> { UserReportsDaoImpl() }
    single { UserReportsDaoImpl() }
}
