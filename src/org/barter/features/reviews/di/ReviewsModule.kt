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
    
    single<RiskPatternDao> { RiskPatternDaoImpl() }
    single { RiskPatternDaoImpl() }
    
    // Risk Analysis Services
    single { DevicePatternDetectionService(get()) }
    single { IpPatternDetectionService(get()) }
    single { LocationPatternDetectionService(get()) }
    
    // Core Services
    single { ReviewEligibilityService() }
    single { RiskAnalysisService(get(), get(), get()) }
    single { ReviewWeightService() }
    single { ReputationCalculationService(get()) }
    single { BlindReviewService() }
}
