package app.bartering.features.attributes.model

import kotlinx.serialization.Serializable

@Serializable
data class AttributesRequest(val userId: String, val attributesRelevancyData: Map<String, Double>)