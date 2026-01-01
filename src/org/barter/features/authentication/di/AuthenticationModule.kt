package org.barter.features.authentication.di

import org.barter.features.profile.dao.UserProfileDaoImpl
import org.koin.dsl.bind
import org.koin.dsl.module

val authenticationModule = module {
    single { UserProfileDaoImpl() } bind UserProfileDaoImpl::class
}