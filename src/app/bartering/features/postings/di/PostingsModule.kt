package app.bartering.features.postings.di

import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.postings.dao.UserPostingDaoImpl
import app.bartering.features.postings.service.PostingExpiryReminderService
import org.koin.dsl.module

val postingsModule = module {
    single<UserPostingDao> { UserPostingDaoImpl() }
    single { PostingExpiryReminderService(postingDao = get(), notificationOrchestrator = get()) }
}
