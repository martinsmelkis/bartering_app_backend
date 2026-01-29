package app.bartering.features.profile.util

import kotlinx.serialization.json.Json

/**
 * Utility object for parsing JSON data commonly used in profile operations.
 */
object JsonParserUtils {
    
    /**
     * Safely parses a JSON string into a Map of keyword weights.
     * Returns an empty map if the JSON is null, invalid, or cannot be parsed.
     * 
     * @param jsonString The JSON string to parse (expected format: {"keyword1": 0.5, "keyword2": 0.8})
     * @return Map of keywords to their weights, or empty map if parsing fails
     * 
     * @example
     * ```kotlin
     * val keywords = JsonParserUtils.parseKeywordWeights("""{"sports": 0.8, "music": 0.6}""")
     * // Result: mapOf("sports" to 0.8, "music" to 0.6)
     * ```
     */
    fun parseKeywordWeights(jsonString: String?): Map<String, Double> {
        return if (jsonString != null) {
            try {
                Json.decodeFromString<Map<String, Double>>(jsonString)
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }
}
