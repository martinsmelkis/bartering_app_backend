package app.bartering.features.categories.di

import app.bartering.features.categories.dao.CategoriesDaoImpl
import org.koin.dsl.bind
import org.koin.dsl.module

val categoriesModule = module {
    single { CategoriesDaoImpl() } bind CategoriesDaoImpl::class

}