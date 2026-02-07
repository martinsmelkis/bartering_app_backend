package app.bartering.utils

object ValidationUtils {
    
    // Maximum limits to prevent DoS attacks
    const val MAX_ATTRIBUTE_KEY_LENGTH = 50
    const val MAX_DESCRIPTION_LENGTH = 1000
    const val MAX_ATTRIBUTES_PER_REQUEST = 50
    const val MIN_RELEVANCY = 0.0
    const val MAX_RELEVANCY = 1.0
    
    /**
     * Validates and sanitizes an attribute key
     * @return sanitized key or null if invalid
     */
    fun validateAttributeKey(key: String?): String? {
        if (key.isNullOrBlank()) return null
        if (key.length > MAX_ATTRIBUTE_KEY_LENGTH) return null
        return key
    }
    
    /**
     * Validates and sanitizes a description field
     * @return sanitized description or null if invalid
     */
    fun validateDescription(description: String?): String? {
        if (description == null) return null
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            return description.take(MAX_DESCRIPTION_LENGTH)
        }
        
        // Basic XSS prevention - remove HTML tags
        return description.trim()
            .replace(Regex("<[^>]*>"), "")  // Remove HTML tags
            .replace(Regex("javascript:", RegexOption.IGNORE_CASE), "")
            .replace(Regex("on\\w+\\s*=", RegexOption.IGNORE_CASE), "")
    }
    
    /**
     * Validates relevancy score
     * @return clamped value between MIN_RELEVANCY and MAX_RELEVANCY
     */
    fun validateRelevancy(relevancy: Double): Double {
        return when {
            relevancy.isNaN() || relevancy.isInfinite() -> MIN_RELEVANCY
            relevancy < MIN_RELEVANCY -> MIN_RELEVANCY
            relevancy > MAX_RELEVANCY -> MAX_RELEVANCY
            else -> relevancy
        }
    }
    
    /**
     * Validates that a map doesn't exceed size limits
     */
    fun <K, V> validateMapSize(map: Map<K, V>?, maxSize: Int = MAX_ATTRIBUTES_PER_REQUEST): Boolean {
        return map != null && map.size <= maxSize
    }
}
