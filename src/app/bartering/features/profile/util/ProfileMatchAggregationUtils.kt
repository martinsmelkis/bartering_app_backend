package app.bartering.features.profile.util

import app.bartering.features.profile.model.UserProfileWithDistance

/**
 * Utility class for aggregating and combining match results from multiple sources.
 *
 * This class handles:
 * - Deduplication of users appearing in multiple result sets
 * - Weighted scoring based on match source importance
 * - Score combination (additive for users appearing in multiple sets)
 * - Profile selection (preferring profiles with more complete attribute data)
 * - Final sorting by combined relevance score
 *
 * Used by profile search operations to merge results from different queries
 * (e.g., semantic profile matches + active posting matches).
 */
object ProfileMatchAggregationUtils {

    /**
     * Combines multiple lists of profile matches with different weights and removes duplicates.
     *
     * When the same user appears in multiple lists:
     * - Their scores are combined additively (weighted)
     * - The profile with the most attributes is kept
     * - Final score reflects total relevance across all match sources
     *
     * @param resultSets Map of match source name to list of profiles from that source
     * @param weights Map of source name to weight multiplier (e.g., "profiles" to 0.8, "postings" to 0.2)
     * @return Deduplicated, weighted, sorted list of unique profiles
     */
    fun combineMatchResults(
        resultSets: Map<String, List<UserProfileWithDistance>>,
        weights: Map<String, Double>
    ): List<UserProfileWithDistance> {
        val combinedMap = mutableMapOf<String, UserProfileWithDistance>()

        resultSets.forEach { (setName, profiles) ->
            val weight = weights[setName] ?: 1.0
            profiles.forEach { profile ->
                val userId = profile.profile.userId
                val sanitizedScore = profile.matchRelevancyScore?.let {
                    if (it.isNaN() || it.isInfinite()) 0.0 else it
                } ?: 0.0
                val weightedScore = sanitizedScore * weight

                val existing = combinedMap[userId]
                if (existing == null) {
                    // First time seeing this user - store with weighted score
                    combinedMap[userId] = profile.copy(
                        matchRelevancyScore = weightedScore
                    )
                } else {
                    // User already in results - combine scores and keep best profile
                    val existingScore = existing.matchRelevancyScore?.let {
                        if (it.isNaN() || it.isInfinite()) 0.0 else it
                    } ?: 0.0
                    val combinedScore = existingScore + weightedScore

                    // Prefer profile with more attributes (more complete data)
                    val betterProfile = if (profile.profile.attributes.size > existing.profile.attributes.size) {
                        profile
                    } else {
                        existing
                    }

                    combinedMap[userId] = betterProfile.copy(
                        matchRelevancyScore = combinedScore
                    )
                }
            }
        }

        // Sort by combined score descending (highest relevance first)
        return combinedMap.values
            .sortedByDescending { it.matchRelevancyScore ?: 0.0 }
    }

    /**
     * Simple deduplication without weighting.
     * Keeps the profile with the highest score when duplicates are found.
     *
     * @param profiles List of profiles that may contain duplicates
     * @return Deduplicated list with best score for each user
     */
    fun deduplicateKeepBestScore(
        profiles: List<UserProfileWithDistance>
    ): List<UserProfileWithDistance> {
        val bestByUser = mutableMapOf<String, UserProfileWithDistance>()

        profiles.forEach { profile ->
            val userId = profile.profile.userId
            val existing = bestByUser[userId]

            if (existing == null) {
                bestByUser[userId] = profile
            } else {
                val existingScore = existing.matchRelevancyScore ?: 0.0
                val newScore = profile.matchRelevancyScore ?: 0.0

                if (newScore > existingScore) {
                    bestByUser[userId] = profile
                }
            }
        }

        return bestByUser.values.toList()
    }

    /**
     * Calculates a weighted score for a single match source.
     * Sanitizes the score (handles NaN/Infinity) and applies weight.
     *
     * @param rawScore The original match score (may be null, NaN, or Infinity)
     * @param weight Weight multiplier to apply
     * @return Sanitized weighted score
     */
    fun calculateWeightedScore(rawScore: Double?, weight: Double): Double {
        val sanitized = rawScore?.let {
            if (it.isNaN() || it.isInfinite()) 0.0 else it
        } ?: 0.0
        return sanitized * weight
    }

    /**
     * Merges two profiles, keeping the best attributes from each.
     * Useful when you want to combine profile data from different sources.
     *
     * @param profile1 First profile
     * @param profile2 Second profile
     * @return New profile with combined best attributes
     */
    fun mergeProfiles(
        profile1: UserProfileWithDistance,
        profile2: UserProfileWithDistance
    ): UserProfileWithDistance {
        // Use profile with more attributes as base
        val (base, other) = if (profile1.profile.attributes.size >= profile2.profile.attributes.size) {
            profile1 to profile2
        } else {
            profile2 to profile1
        }

        // Combine scores if both present
        val score1 = profile1.matchRelevancyScore ?: 0.0
        val score2 = profile2.matchRelevancyScore ?: 0.0
        val combinedScore = when {
            score1.isNaN() || score1.isInfinite() -> score2
            score2.isNaN() || score2.isInfinite() -> score1
            else -> score1 + score2
        }

        // Use closer distance if available
        val bestDistance = when {
            profile1.distanceKm == null -> profile2.distanceKm
            profile2.distanceKm == null -> profile1.distanceKm
            else -> minOf(profile1.distanceKm, profile2.distanceKm)
        }

        return base.copy(
            matchRelevancyScore = if (combinedScore > 0) combinedScore else null,
            distanceKm = bestDistance
        )
    }

    /**
     * Applies a boost factor to scores for specific match types.
     * Useful for promoting certain types of matches (e.g., mutual interest).
     *
     * @param profiles List of profiles to boost
     * @param boostFactor Amount to add to the score (e.g., 0.05 for 5% boost)
     * @param predicate Function to determine which profiles get the boost
     * @return Profiles with boosted scores
     */
    fun applyScoreBoost(
        profiles: List<UserProfileWithDistance>,
        boostFactor: Double,
        predicate: (UserProfileWithDistance) -> Boolean
    ): List<UserProfileWithDistance> {
        return profiles.map { profile ->
            if (predicate(profile)) {
                val currentScore = profile.matchRelevancyScore ?: 0.0
                profile.copy(matchRelevancyScore = currentScore + boostFactor)
            } else {
                profile
            }
        }
    }

    /**
     * Normalizes scores to 0.0-1.0 range within the result set.
     * Useful when combining scores from different algorithms with different scales.
     *
     * @param profiles List of profiles with scores to normalize
     * @return Profiles with normalized scores
     */
    fun normalizeScores(
        profiles: List<UserProfileWithDistance>
    ): List<UserProfileWithDistance> {
        if (profiles.isEmpty()) return profiles

        val scores = profiles.mapNotNull { it.matchRelevancyScore }
            .filter { !it.isNaN() && !it.isInfinite() }

        if (scores.isEmpty()) return profiles

        val minScore = scores.minOrNull() ?: 0.0
        val maxScore = scores.maxOrNull() ?: 1.0
        val range = maxScore - minScore

        if (range == 0.0) return profiles

        return profiles.map { profile ->
            val score = profile.matchRelevancyScore
            if (score == null || score.isNaN() || score.isInfinite()) {
                profile
            } else {
                val normalized = (score - minScore) / range
                profile.copy(matchRelevancyScore = normalized)
            }
        }
    }
}
