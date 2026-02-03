package app.bartering.features.chat.di

import app.bartering.features.chat.dao.ChatAnalyticsDao
import app.bartering.features.chat.dao.ChatAnalyticsDaoImpl
import app.bartering.features.chat.manager.ConnectionManager
import org.koin.dsl.module

val chatModule = module {
    // Connection Manager (WebSocket connections)
    single { ConnectionManager() }
    
    // DAOs
    single<ChatAnalyticsDao> { ChatAnalyticsDaoImpl() }
    single { ChatAnalyticsDaoImpl() }
}
