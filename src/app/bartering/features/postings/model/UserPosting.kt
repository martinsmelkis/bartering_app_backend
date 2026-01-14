package app.bartering.features.postings.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Represents a user posting (offer or interest/need)
 */
@Serializable
data class UserPosting(
    val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val value: Double? = null,
    @Serializable(with = InstantSerializer::class)
    val expiresAt: Instant? = null,
    val imageUrls: List<String> = emptyList(),
    val isOffer: Boolean,
    val status: PostingStatus = PostingStatus.ACTIVE,
    val attributes: List<PostingAttributeDto> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant
)

/**
 * DTO for creating or updating a user posting
 */
@Serializable
data class UserPostingRequest(
    val id: String? = null, // Null for new postings, present for updates
    val title: String,
    val description: String,
    val value: Double? = null,
    @Serializable(with = InstantSerializer::class)
    val expiresAt: Instant? = null,
    val imageUrls: List<String> = emptyList(),
    val isOffer: Boolean,
    val attributes: List<PostingAttributeDto> = emptyList()
)

/**
 * Represents an attribute linked to a posting
 */
@Serializable
data class PostingAttributeDto(
    val attributeId: String,
    val relevancy: Double = 1.0
)

/**
 * Status of a posting
 */
@Serializable
enum class PostingStatus {
    ACTIVE,
    EXPIRED,
    DELETED,
    FULFILLED
}

/**
 * Response containing a posting with additional metadata
 */
@Serializable
data class UserPostingWithDistance(
    val posting: UserPosting,
    val distanceKm: Double? = null,
    val similarityScore: Double? = null,
    val matchRelevancyScore: Double? = null
)

/**
 * Serializer for Instant to handle timestamp conversion
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}
