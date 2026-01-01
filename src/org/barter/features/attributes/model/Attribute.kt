package org.barter.features.attributes.model

import java.time.Instant

data class Attribute(
    val id: Int,
    val attributeNameKey: String,
    val localizationKey: String,
    val customUserAttrText: String?,
    val isApproved: Boolean,
    // The embedding vector for this attribute, used for similarity searches.
    val embedding: List<Float>?,
    val createdAt: Instant,
    val updatedAt: Instant
)
