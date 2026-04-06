package app.bartering.features.purchases.di

import app.bartering.features.purchases.dao.PurchasesDao
import app.bartering.features.purchases.dao.PurchasesDaoImpl
import app.bartering.features.purchases.service.PurchasesService
import app.bartering.features.purchases.service.PurchasesServiceImpl
import org.koin.dsl.module

val purchasesModule = module {
    single<PurchasesDao> { PurchasesDaoImpl() }
    single { PurchasesDaoImpl() }

    single<PurchasesService> { PurchasesServiceImpl(get(), get()) }
    single { PurchasesServiceImpl(get(), get()) }
}
