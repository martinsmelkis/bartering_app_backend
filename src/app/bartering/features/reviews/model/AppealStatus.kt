package app.bartering.features.reviews.model

/**
 * Status of a review appeal/dispute.
 */
enum class AppealStatus(val value: String) {
    /**
     * Appeal submitted but not yet reviewed.
     */
    PENDING("pending"),

    /**
     * Appeal is being reviewed by moderators.
     */
    UNDER_REVIEW("under_review"),

    /**
     * Appeal approved - review removed or modified.
     */
    APPROVED("approved"),

    /**
     * Appeal rejected - review stands.
     */
    REJECTED("rejected"),

    /**
     * Additional evidence requested from appellant.
     */
    EVIDENCE_REQUESTED("evidence_requested");

    companion object {
        fun fromString(value: String): AppealStatus? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
