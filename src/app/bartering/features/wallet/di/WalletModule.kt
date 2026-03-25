package app.bartering.features.wallet.di

import app.bartering.features.wallet.dao.WalletDao
import app.bartering.features.wallet.dao.WalletDaoImpl
import app.bartering.features.wallet.service.WalletService
import app.bartering.features.wallet.service.WalletServiceImpl
import org.koin.dsl.module

val walletModule = module {
    single<WalletDao> { WalletDaoImpl() }
    single { WalletDaoImpl() }

    single<WalletService> { WalletServiceImpl(get()) }
    single { WalletServiceImpl(get()) }
}
