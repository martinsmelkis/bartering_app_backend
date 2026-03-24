package app.bartering.features.analytics.di

import app.bartering.features.analytics.dao.UserDailyActivityStatsDao
import app.bartering.features.analytics.dao.UserDailyActivityStatsDaoImpl
import app.bartering.features.analytics.service.UserDailyActivityStatsService
import app.bartering.features.profile.dao.UserProfileDao
import org.koin.dsl.module

val analyticsModule = module {
    single<UserDailyActivityStatsDao> { UserDailyActivityStatsDaoImpl() }
    single {
        UserDailyActivityStatsService(
            statsDao = get<UserDailyActivityStatsDao>(),
            userProfileDao = get<UserProfileDao>()
        )
    }
}
