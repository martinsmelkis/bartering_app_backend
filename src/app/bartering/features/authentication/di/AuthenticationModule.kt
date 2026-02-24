package app.bartering.features.authentication.di

import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.mapper.AuthenticationMapperImpl
import org.koin.dsl.module

val authenticationModule = module {
    single { AuthenticationMapperImpl() }
    single { AuthenticationDaoImpl(AuthenticationMapperImpl()) }
}
