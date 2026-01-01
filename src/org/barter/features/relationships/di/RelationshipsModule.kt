package org.barter.features.relationships.di

import org.barter.features.relationships.dao.UserRelationshipsDao
import org.barter.features.relationships.dao.UserRelationshipsDaoImpl
import org.koin.dsl.module

val relationshipsModule = module {
    single<UserRelationshipsDao> { UserRelationshipsDaoImpl() }
    single { UserRelationshipsDaoImpl() }
}
