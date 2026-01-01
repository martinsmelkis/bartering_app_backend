package org.barter.features.postings.di

import org.barter.features.postings.dao.UserPostingDao
import org.barter.features.postings.dao.UserPostingDaoImpl
import org.koin.dsl.module

val postingsModule = module {
    single<UserPostingDao> { UserPostingDaoImpl() }
}
