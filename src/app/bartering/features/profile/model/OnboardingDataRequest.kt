package app.bartering.features.profile.model

import kotlinx.serialization.Serializable

@Serializable
data class OnboardingDataRequest(
    val userId: String,
    val onboardingKeyNamesToWeights: Map<String, Double>)
