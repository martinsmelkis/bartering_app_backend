package app.bartering.utils

import java.security.MessageDigest

object HashUtils {

    /**
     * Generates a SHA-256 hash for a map of profile keywords.
     * The map is sorted to ensure a consistent hash regardless of the original order.
     */
    fun sha256(input: Map<String, Double>): String {
        // Sort the map by key to create a canonical string representation
        val canonicalString = input.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
        return hashString(canonicalString)
    }

    private fun hashString(input: String): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
