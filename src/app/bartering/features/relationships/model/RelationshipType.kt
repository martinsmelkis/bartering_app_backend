package app.bartering.features.relationships.model

/**
 * Defines the types of relationships a user can have with another user.
 * These are stored in the user_relationships table.
 */
enum class RelationshipType(val value: String) {
    /**
     * User has favorited/starred another user for quick access.
     * This is a one-way relationship.
     */
    FAVORITE("favorite"),

    /**
     * Mutual friendship - both users have accepted a friend request.
     * When one user sends a friend request, it creates a FRIEND_REQUEST_SENT.
     * When accepted, both users get FRIEND relationship entries.
     */
    FRIEND("friend"),

    /**
     * User has sent a friend request that hasn't been accepted yet.
     * One-way relationship.
     */
    FRIEND_REQUEST_SENT("friend_request_sent"),

    /**
     * User has received a friend request (not explicitly stored, derived from FRIEND_REQUEST_SENT).
     */
    FRIEND_REQUEST_RECEIVED("friend_request_received"),

    /**
     * Users have chatted before. Created automatically when users exchange messages.
     * Two-way relationship (both users get this entry).
     */
    CHATTED("chatted"),

    /**
     * User has blocked another user. Prevents all interactions.
     * One-way relationship.
     */
    BLOCKED("blocked"),

    /**
     * User has hidden another user from search/discovery results.
     * One-way relationship.
     */
    HIDDEN("hidden"),

    /**
     * User has reported another user for inappropriate behavior.
     * One-way relationship for moderation purposes.
     */
    REPORTED("reported"),

    /**
     * User has completed a successful barter/trade with another user.
     * Two-way relationship (both users get this entry).
     */
    TRADED("traded"),

    /**
     * User is interested in trading with this user (like a "pending trade interest").
     * One-way relationship.
     */
    TRADE_INTERESTED("trade_interested");

    companion object {
        fun fromString(value: String): RelationshipType? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
