package app.bartering.features.chat.di

import app.bartering.features.chat.dao.ChatAnalyticsDao
import app.bartering.features.chat.dao.ChatAnalyticsDaoImpl
import org.koin.dsl.module

val chatModule = module {
    // DAOs
    single<ChatAnalyticsDao> { ChatAnalyticsDaoImpl() }
    single { ChatAnalyticsDaoImpl() }
}
