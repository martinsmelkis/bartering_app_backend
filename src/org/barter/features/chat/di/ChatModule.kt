package org.barter.features.chat.di

import org.barter.features.chat.dao.ChatAnalyticsDao
import org.barter.features.chat.dao.ChatAnalyticsDaoImpl
import org.koin.dsl.module

val chatModule = module {
    // DAOs
    single<ChatAnalyticsDao> { ChatAnalyticsDaoImpl() }
    single { ChatAnalyticsDaoImpl() }
}
