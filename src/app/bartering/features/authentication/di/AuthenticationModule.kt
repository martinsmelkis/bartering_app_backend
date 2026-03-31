package app.bartering.features.authentication.di

import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.mapper.AuthenticationMapperImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

val authenticationModule = module {
    single { AuthenticationMapperImpl() }
    single { AuthenticationDaoImpl(AuthenticationMapperImpl()) }
    single<CoroutineScope>(named("authBackgroundScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    single<CoroutineScope>(named("appBackgroundScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
