package app.bartering.features.reviews.model

/**
 * Priority level for moderation queue items.
 */
enum class ModerationPriority(val value: String) {
    /**
     * Low priority - routine review.
     */
    LOW("low"),

    /**
     * Medium priority - standard investigation needed.
     */
    MEDIUM("medium"),

    /**
     * High priority - serious allegation or pattern detected.
     */
    HIGH("high"),

    /**
     * Urgent - immediate attention required (e.g., safety concern).
     */
    URGENT("urgent");

    companion object {
        fun fromString(value: String): ModerationPriority? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
