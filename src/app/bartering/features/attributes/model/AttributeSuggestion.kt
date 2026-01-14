package app.bartering.features.attributes.model

import kotlinx.serialization.Serializable

@Serializable
data class AttributeSuggestion(
    val attribute: String,
    val relevancyScore: Double,
    val uiStyleHint: String?
)
