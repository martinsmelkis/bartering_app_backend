package app.bartering.features.federation.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.Instant

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Custom serializer for java.time.Instant to handle JSON serialization.
 * Supports both epoch milliseconds (Long) and ISO-8601 strings for compatibility.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        // Serialize as epoch milliseconds for compactness
        encoder.encodeLong(value.toEpochMilli())
    }

    override fun deserialize(decoder: Decoder): Instant {
        // Use JSON element to handle both Long and String formats
        val json = decoder as? kotlinx.serialization.json.JsonDecoder
        return if (json != null) {
            when (val element = json.decodeJsonElement()) {
                is JsonPrimitive -> {
                    element.longOrNull?.let { Instant.ofEpochMilli(it) }
                        ?: Instant.parse(element.content)
                }
                else -> throw IllegalArgumentException("Unexpected JSON type for Instant: $element")
            }
        } else {
            // Fallback for non-JSON formats
            Instant.ofEpochMilli(decoder.decodeLong())
        }
    }
}

/**
 * Custom serializer for java.math.BigDecimal to handle JSON serialization.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        return BigDecimal(decoder.decodeString())
    }
}
