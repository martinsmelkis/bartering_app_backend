package app.bartering.features.profile.util

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory

/**
 * Utility class for building SQL query filters related to profile matching and search.
 *
 * This class contains pure functions for generating SQL WHERE clause fragments
 * based on attribute types (seeking/offering) and semantic embedding availability.
 * No database access - just string building logic.
 */
object ProfileQueryBuilderUtils {

    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Builds a SQL filter clause for user attribute types.
     *
     * Used to filter search results based on whether users are seeking or offering items.
     * Generates SQL fragments like "AND ua.type = 'SEEKING'" or "AND ua.type IN ('PROVIDING', 'SHARING')".
     *
     * @param seeking If true, include users seeking items (SEEKING type)
     * @param offering If true, include users offering items (PROVIDING/SHARING types)
     * @return SQL WHERE clause fragment (empty string if no filtering needed)
     *
     * Examples:
     * - seeking=true, offering=false → "AND ua.type = 'SEEKING'"
     * - seeking=false, offering=true → "AND ua.type IN ('PROVIDING', 'SHARING')"
     * - seeking=true, offering=true → "" (both allowed, no filter)
     */
    fun buildAttributeTypeSQLFilter(seeking: Boolean?, offering: Boolean?): String {
        return when {
            seeking == true && offering == true -> ""  // Both types allowed
            seeking == true && offering == false -> "AND ua.type = 'SEEKING'"
            seeking == false && offering == true -> "AND ua.type IN ('PROVIDING', 'SHARING')"
            seeking == null && offering == null -> ""  // No filter when both are null
            else -> ""  // Default: no filter
        }
    }

    /**
     * Builds a SQL filter clause for semantic embedding availability.
     *
     * Used to filter users based on whether they have computed semantic embeddings
     * for their haves (embedding_haves) or needs (embedding_needs).
     *
     * @param seeking If true, require embedding_needs to be present
     * @param offering If true, require embedding_haves to be present
     * @return SQL WHERE clause condition for embedding presence
     *
     * Examples:
     * - seeking=true, offering=false → "usp.embedding_needs IS NOT NULL"
     * - seeking=false, offering=true → "usp.embedding_haves IS NOT NULL"
     * - seeking=true, offering=true → "usp.embedding_haves IS NOT NULL OR usp.embedding_needs IS NOT NULL"
     * - seeking=false, offering=false → "FALSE" (no results possible)
     */
    fun buildSemanticEmbeddingSQLFilter(seeking: Boolean?, offering: Boolean?): String {
        return when {
            seeking == false && offering == false -> "FALSE"  // Neither type allowed
            seeking == true && offering == false -> "usp.embedding_needs IS NOT NULL"
            seeking == false && offering == true -> "usp.embedding_haves IS NOT NULL"
            else -> "usp.embedding_haves IS NOT NULL OR usp.embedding_needs IS NOT NULL"  // Default: both
        }
    }

    /**
     * Builds a complete SQL WHERE clause combining multiple filters.
     *
     * Combines attribute type filters with semantic embedding filters,
     * ensuring proper spacing and 'AND' conjunctions.
     *
     * @param userId User ID to exclude from results (set to null for no exclusion)
     * @param seeking Filter for users seeking items
     * @param offering Filter for users offering items
     * @param requireEmbeddings If true, only include users with semantic embeddings
     * @return Complete SQL WHERE clause (may be empty string if no filters)
     */
    fun buildCompleteWhereClause(
        userId: String?,
        seeking: Boolean?,
        offering: Boolean?,
        requireEmbeddings: Boolean = true
    ): String {
        val conditions = mutableListOf<String>()

        // Exclude current user
        if (userId != null) {
            conditions.add("u.id != ?")
        }

        // Attribute type filter
        val attributeFilter = buildAttributeTypeSQLFilter(seeking, offering)
        if (attributeFilter.isNotBlank()) {
            conditions.add(attributeFilter.removePrefix("AND ").trim())
        }

        // Semantic embedding requirement
        if (requireEmbeddings) {
            val embeddingFilter = buildSemanticEmbeddingSQLFilter(seeking, offering)
            if (embeddingFilter != "FALSE") {
                conditions.add("($embeddingFilter)")
            } else {
                log.warn("Both seeking and offering are false - query will return no results")
                conditions.add("FALSE")
            }
        }

        return if (conditions.isEmpty()) {
            ""
        } else {
            "WHERE " + conditions.joinToString(" AND ")
        }
    }

    /**
     * Builds a SQL ORDER BY clause for match ranking.
     *
     * Orders results by semantic similarity score (when available) combined with
     * geographic proximity.
     *
     * @param hasLocation If true, include distance-based ordering
     * @param useSemanticScore If true, include semantic similarity ordering
     * @return SQL ORDER BY clause fragment
     */
    fun buildOrderByClause(
        hasLocation: Boolean = true,
        useSemanticScore: Boolean = true
    ): String {
        val orderParts = mutableListOf<String>()

        if (useSemanticScore) {
            orderParts.add("semantic_score DESC NULLS LAST")
        }

        if (hasLocation) {
            orderParts.add("distance_meters ASC NULLS LAST")
        }

        // Always have a fallback to ensure deterministic ordering
        orderParts.add("u.id ASC")

        return "ORDER BY " + orderParts.joinToString(", ")
    }

    /**
     * Sanitizes a search text string for safe use in SQL queries.
     *
     * Removes or escapes characters that could interfere with SQL syntax
     * or similarity search functions.
     *
     * @param searchText Raw search input
     * @return Sanitized search text safe for SQL similarity functions
     */
    fun sanitizeSearchText(searchText: String): String {
        return searchText
            .replace("'", "''")  // SQL escape single quotes
            .replace("%", "\\%")  // Escape LIKE wildcards
            .replace("_", "\\_")
            .trim()
            .take(200)  // Limit length to prevent abuse
    }

    /**
     * Calculates a similarity threshold multiplier based on user density.
     *
     * Lower user density areas get lower thresholds (more permissive matching)
     * to ensure users still get meaningful results.
     *
     * @param nearbyUserCount Number of users in search radius
     * @return Threshold multiplier (0.0-1.0) to apply to base threshold
     */
    fun calculateThresholdMultiplier(nearbyUserCount: Int): Double {
        return when {
            nearbyUserCount < 10 -> 0.75   // Rural/sparse: be more permissive
            nearbyUserCount < 20 -> 0.9    // Suburban: slight adjustment
            else -> 1.0                    // Urban: strict matching
        }
    }

    /**
     * Formats a distance in kilometers for display.
     *
     * Shows meters for short distances, kilometers for longer ones.
     *
     * @param distanceKm Distance in kilometers
     * @return Human-readable distance string
     */
    fun formatDistance(distanceKm: Double?): String {
        return when {
            distanceKm == null -> "Unknown distance"
            distanceKm < 0.1 -> "${(distanceKm * 1000).toInt()} m"  // Less than 100m → show meters
            distanceKm < 1.0 -> "${(distanceKm * 100).toInt() / 100.0} km"  // Less than 1km → show 2 decimals
            distanceKm < 10.0 -> "${(distanceKm * 10).toInt() / 10.0} km"   // Less than 10km → show 1 decimal
            else -> "${distanceKm.toInt()} km"  // 10km+ → show whole km
        }
    }
}
