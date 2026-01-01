package org.barter.features.profile.di

import org.barter.features.attributes.dao.AttributesDaoImpl
import org.barter.features.attributes.dao.UserAttributesDaoImpl
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.authentication.dao.mapper.AuthenticationMapperImpl
import org.barter.features.profile.dao.UserProfileDaoImpl
import org.koin.dsl.bind
import org.koin.dsl.module

val profilesModule = module {
    single { AuthenticationDaoImpl(AuthenticationMapperImpl()) } bind AuthenticationDaoImpl::class
    single { AttributesDaoImpl() } bind AttributesDaoImpl::class
    single { UserProfileDaoImpl() } bind UserProfileDaoImpl::class
    single { UserAttributesDaoImpl() } bind UserAttributesDaoImpl::class
}