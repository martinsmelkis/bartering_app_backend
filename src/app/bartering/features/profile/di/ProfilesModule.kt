package app.bartering.features.profile.di

import app.bartering.features.attributes.dao.AttributesDaoImpl
import app.bartering.features.attributes.dao.UserAttributesDaoImpl
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.dao.mapper.AuthenticationMapperImpl
import app.bartering.features.profile.dao.UserProfileDao
import app.bartering.features.profile.dao.UserProfileDaoImpl
import org.koin.dsl.bind
import org.koin.dsl.module

val profilesModule = module {
    single { AuthenticationDaoImpl(AuthenticationMapperImpl()) } bind AuthenticationDaoImpl::class
    single { AttributesDaoImpl() } bind AttributesDaoImpl::class
    single<UserProfileDao> { UserProfileDaoImpl() }
    single { get<UserProfileDao>() as UserProfileDaoImpl } // Bind impl for backward compatibility
    single { UserAttributesDaoImpl() } bind UserAttributesDaoImpl::class
}