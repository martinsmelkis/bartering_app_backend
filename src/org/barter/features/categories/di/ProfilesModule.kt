package org.barter.features.categories.di

import org.barter.features.categories.dao.CategoriesDaoImpl
import org.koin.dsl.bind
import org.koin.dsl.module

val categoriesModule = module {
    single { CategoriesDaoImpl() } bind CategoriesDaoImpl::class

}