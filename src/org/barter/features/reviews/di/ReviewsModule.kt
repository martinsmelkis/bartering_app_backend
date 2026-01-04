package org.barter.features.reviews.di

import org.barter.features.reviews.dao.*
import org.barter.features.reviews.service.*
import org.koin.dsl.module

val reviewsModule = module {
    // DAOs
    single<BarterTransactionDao> { BarterTransactionDaoImpl() }
    single { BarterTransactionDaoImpl() }
    
    single<ReviewDao> { ReviewDaoImpl() }
    single { ReviewDaoImpl() }
    
    single<ReputationDao> { ReputationDaoImpl() }
    single { ReputationDaoImpl() }
    
    // Services
    single { ReviewEligibilityService() }
    single { RiskAnalysisService() }
    single { ReviewWeightService() }
    single { ReputationCalculationService(get()) }
    single { BlindReviewService() }
}
