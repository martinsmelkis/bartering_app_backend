package app.bartering.features.postings.di

import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.postings.dao.UserPostingDaoImpl
import org.koin.dsl.module

val postingsModule = module {
    single<UserPostingDao> { UserPostingDaoImpl() }
}
