package app.bartering.model

/**
 * Enum defining the type of bidirectional match search to perform.
 */
enum class BidirectionalMatchType {
    COMPLEMENTARY,  // my_needs<->their_haves AND my_haves<->their_needs
    SIMILAR         // my_haves<->their_haves AND my_needs<->their_needs
}