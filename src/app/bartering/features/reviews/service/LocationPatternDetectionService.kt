package app.bartering.features.reviews.service

import app.bartering.features.reviews.dao.RiskPatternDao
import app.bartering.features.reviews.model.*
import java.time.Instant
import kotlin.math.*

/**
 * Service for detecting suspicious location change patterns.
 * Identifies location hopping, coordinated movements, and impossible travel patterns.
 * This service focuses on profile location changes rather than continuous GPS tracking.
 */
class LocationPatternDetectionService(
    private val riskPatternDao: RiskPatternDao
) {

    companion object {
        // Distance thresholds for location change analysis
        const val SAME_LOCATION_THRESHOLD_KM = 10.0 // 10 km = "same general area"
        const val RAPID_HOPPING_THRESHOLD_KM = 50.0 // 50 km
        const val IMPOSSIBLE_MOVEMENT_THRESHOLD_KM = 500.0 // 500 km
        const val IMPOSSIBLE_MOVEMENT_TIME_HOURS = 6 // Hours

        // Frequency thresholds
        const val FREQUENT_CHANGES_COUNT = 5 // Number of changes
        const val FREQUENT_CHANGES_DAYS = 30 // Time window in days

        // Coordinated change detection
        const val COORDINATED_CHANGE_TIME_WINDOW_MINUTES = 1440 // 24 hours
        const val COORDINATED_CHANGE_RADIUS_KM = 50.0 // 50 km radius
        const val COORDINATED_CHANGE_THRESHOLD_USERS = 3 // Min users to flag as coordinated
    }

    /**
     * Tracks a profile location change.
     * Called when user updates their location in profile settings.
     */
    suspend fun trackLocationChange(
        userId: String,
        oldLatitude: Double?,
        oldLongitude: Double?,
        newLatitude: Double,
        newLongitude: Double
    ) {
        // Validate new coordinates
        if (!isValidCoordinate(newLatitude, newLongitude)) {
            return // Ignore invalid coordinates
        }

        // Validate old coordinates if provided, null out if invalid
        val validOldLatitude = if (oldLatitude != null && oldLongitude != null &&
                isValidCoordinate(oldLatitude, oldLongitude)) {
            oldLatitude
        } else {
            null
        }
        val validOldLongitude = if (validOldLatitude != null) oldLongitude else null

        riskPatternDao.trackLocationChange(
            LocationChangeData(
                userId = userId,
                oldLatitude = validOldLatitude,
                oldLongitude = validOldLongitude,
                newLatitude = newLatitude,
                newLongitude = newLongitude,
                changedAt = Instant.now()
            )
        )

        // Auto-detect anomalies after tracking
        val patterns = detectLocationChangeAnomalies(userId)
        patterns.forEach { pattern ->
            riskPatternDao.recordSuspiciousPattern(pattern)
        }

        // Auto-detect coordinated changes for this new location
        checkCoordinatedLocationChange(userId, newLatitude, newLongitude)
    }

    /**
     * Detects suspicious location change patterns for a user.
     */
    suspend fun detectLocationChangeAnomalies(userId: String): List<SuspiciousPattern> {
        val patterns = mutableListOf<SuspiciousPattern>()
        val analysis = riskPatternDao.analyzeLocationChangePattern(userId) ?: return patterns

        // Impossible movement detected
        if (analysis.impossibleMovementDetected) {
            patterns.add(
                SuspiciousPattern(
                    type = PatternType.LOCATION_HOPPING,
                    description = "Impossible location changes detected for user $userId",
                    severity = PatternSeverity.HIGH,
                    affectedUsers = listOf(userId),
                    evidence = mapOf(
                        "suspicious_patterns" to analysis.suspiciousPatterns.joinToString("; "),
                        "max_distance_km" to String.format("%.2f", analysis.maxDistanceBetweenConsecutiveChanges ?: 0.0)
                    )
                )
            )
        }

        // Location hopping detected
        if (analysis.locationHoppingDetected && !analysis.impossibleMovementDetected) {
            patterns.add(
                SuspiciousPattern(
                    type = PatternType.LOCATION_HOPPING,
                    description = "Frequent location hopping detected for user $userId",
                    severity = PatternSeverity.MEDIUM,
                    affectedUsers = listOf(userId),
                    evidence = mapOf(
                        "patterns" to analysis.suspiciousPatterns.joinToString("; "),
                        "avg_distance_km" to String.format("%.2f", analysis.averageDistanceBetweenChanges ?: 0.0)
                    )
                )
            )
        }

        // Frequent changes detected
        if (analysis.frequentChangesDetected) {
            patterns.add(
                SuspiciousPattern(
                    type = PatternType.LOCATION_HOPPING,
                    description = "Frequent profile location changes for user $userId",
                    severity = PatternSeverity.MEDIUM,
                    affectedUsers = listOf(userId),
                    evidence = mapOf(
                        "change_count" to analysis.changes.size.toString(),
                        "suspicious_patterns" to analysis.suspiciousPatterns.joinToString("; ")
                    )
                )
            )
        }

        return patterns
    }

    /**
     * Checks for coordinated location changes (multiple users moving to same area).
     */
    suspend fun checkCoordinatedLocationChange(
        userId: String,
        latitude: Double,
        longitude: Double
    ): SuspiciousPattern? {
        val suspectUsers = riskPatternDao.detectCoordinatedLocationChanges(
            latitude = latitude,
            longitude = longitude,
            radiusKm = COORDINATED_CHANGE_RADIUS_KM,
            timeWindowMinutes = COORDINATED_CHANGE_TIME_WINDOW_MINUTES,
            thresholdUsers = COORDINATED_CHANGE_THRESHOLD_USERS
        )

        if (userId !in suspectUsers) {
            return null // Not part of coordinated group
        }

        return SuspiciousPattern(
            type = PatternType.COORDINATED_LOCATION_CHANGE,
            description = "${suspectUsers.size} users changed to same area in ${COORDINATED_CHANGE_TIME_WINDOW_MINUTES / 60} hours",
            severity = when {
                suspectUsers.size >= 5 -> PatternSeverity.HIGH
                else -> PatternSeverity.MEDIUM
            },
            affectedUsers = suspectUsers,
            evidence = mapOf(
                "user_count" to suspectUsers.size.toString(),
                "time_window_hours" to (COORDINATED_CHANGE_TIME_WINDOW_MINUTES / 60).toString(),
                "radius_km" to COORDINATED_CHANGE_RADIUS_KM.toString(),
                "target_area" to "${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)}"
            )
        )
    }

    /**
     * Checks if two users' current locations are similar (potential Sybil attack).
     */
    suspend fun checkLocationProximity(user1Id: String, user2Id: String): SuspiciousPattern? {
        val distance = riskPatternDao.calculateLocationDistance(user1Id, user2Id) ?: return null

        // If users are within same location threshold, they might be same person
        if (distance < SAME_LOCATION_THRESHOLD_KM * 1000) {
            return SuspiciousPattern(
                type = PatternType.LOCATION_HOPPING,
                description = "Users are in same general location (${String.format("%.1f", distance / 1000)}km apart)",
                severity = PatternSeverity.MEDIUM,
                affectedUsers = listOf(user1Id, user2Id),
                evidence = mapOf(
                    "distance_km" to String.format("%.2f", distance / 1000),
                    "risk" to "Same general area suggests same person or coordinated activity"
                )
            )
        }

        return null
    }

    /**
     * Calculates location change risk score for a user.
     */
    suspend fun calculateLocationRiskScore(userId: String): Double {
        val patterns = detectLocationChangeAnomalies(userId)

        if (patterns.isEmpty()) return 0.0

        val analysis = riskPatternDao.analyzeLocationChangePattern(userId)

        // Weight factors
        var score = 0.0

        if (analysis != null) {
            // Impossible movement is high risk
            if (analysis.impossibleMovementDetected) score += 0.5

            // Location hopping is medium risk
            if (analysis.locationHoppingDetected) score += 0.3

            // Frequent changes is low-medium risk
            if (analysis.frequentChangesDetected) score += 0.2

            // Multiple suspicious patterns increase risk
            score += patterns.size * 0.1
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Checks if users have similar location change histories (pattern matching).
     * This helps detect coordinated account networks.
     */
    suspend fun checkLocationChangePatternSimilarity(user1Id: String, user2Id: String): SuspiciousPattern? {
        val user1Changes = riskPatternDao.getUserLocationChanges(user1Id, 10)
        val user2Changes = riskPatternDao.getUserLocationChanges(user2Id, 10)

        if (user1Changes.size < 3 || user2Changes.size < 3) return null

        // Check for overlapping location changes
        val matches = mutableListOf<String>()

        for (change1 in user1Changes) {
            for (change2 in user2Changes) {
                val distance = calculateDistance(
                    change1.newLatitude, change1.newLongitude,
                    change2.newLatitude, change2.newLongitude
                )

                // If locations are within 50km and within same day window
                if (distance < 50_000) {
                    val timeDiff = abs(change1.changedAt.epochSecond - change2.changedAt.epochSecond)
                    if (timeDiff < 86400) { // 24 hours
                        matches.add(
                            "${String.format("%.1f", distance / 1000)}km apart, " +
                            "${String.format("%.0f", timeDiff / 3600)}h apart"
                        )
                    }
                }
            }
        }

        // Flag if 3+ matching pattern instances
        if (matches.size >= 3) {
            return SuspiciousPattern(
                type = PatternType.COORDINATED_LOCATION_CHANGE,
                description = "Similar location change patterns between accounts",
                severity = PatternSeverity.HIGH,
                affectedUsers = listOf(user1Id, user2Id),
                evidence = mapOf(
                    "matching_patterns" to matches.size.toString(),
                    "examples" to matches.take(3).joinToString("; ")
                )
            )
        }

        return null
    }

    /**
     * Validates GPS coordinates.
     */
    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    /**
     * Calculates distance between two GPS coordinates using Haversine formula.
     * Returns distance in meters.
     */
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    /**
     * Detects if a sequence of location changes represents realistic travel.
     * Simplified version since we don't have dense GPS data.
     */
    suspend fun analyzeLocationChangeRealism(userId: String): SuspiciousPattern? {
        val changes = riskPatternDao.getUserLocationChanges(userId, 20)

        if (changes.size < 2) return null

        var impossibleMovements = 0
        val impossibleSegments = mutableListOf<String>()

        for (i in 0 until changes.size - 1) {
            val change1 = changes[i]
            val change2 = changes[i + 1]

            val distance = calculateDistance(
                change1.newLatitude, change1.newLongitude,
                change2.newLatitude, change2.newLongitude
            )

            val timeDiff = abs(change2.changedAt.epochSecond - change1.changedAt.epochSecond)
            val timeDiffHours = timeDiff / 3600.0

            if (timeDiffHours == 0.0) continue

            // Check for impossible movement (> 500km in < 6 hours for profile changes)
            if (distance > 500_000 && timeDiffHours < 6) {
                impossibleMovements++
                impossibleSegments.add(
                    "${String.format("%.0f", distance / 1000)}km in ${String.format("%.1f", timeDiffHours)}h"
                )
            }
        }

        if (impossibleMovements > 0) {
            return SuspiciousPattern(
                type = PatternType.LOCATION_HOPPING,
                description = "$impossibleMovements impossible movements detected",
                severity = when {
                    impossibleMovements > 2 -> PatternSeverity.HIGH
                    else -> PatternSeverity.MEDIUM
                },
                affectedUsers = listOf(userId),
                evidence = mapOf(
                    "impossible_count" to impossibleMovements.toString(),
                    "examples" to impossibleSegments.take(3).joinToString("; ")
                )
            )
        }

        return null
    }
}