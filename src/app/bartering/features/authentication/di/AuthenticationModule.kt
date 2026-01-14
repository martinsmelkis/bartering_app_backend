package app.bartering.features.authentication.di

import app.bartering.features.profile.dao.UserProfileDaoImpl
import org.koin.dsl.bind
import org.koin.dsl.module

val authenticationModule = module {
    single { UserProfileDaoImpl() } bind UserProfileDaoImpl::class
}