package app.bartering.features.reviews.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.reviews.db.ReviewRiskTrackingTable
import app.bartering.features.reviews.db.RiskPatternsTable
import app.bartering.features.reviews.model.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.less
import org.slf4j.LoggerFactory
import net.postgis.jdbc.geometry.Point
import java.time.Instant
import java.util.UUID
import kotlin.math.*

/**
 * Implementation of RiskPatternDao for tracking and analyzing risk patterns.
 */
class RiskPatternDaoImpl : RiskPatternDao {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    // ========== Device Tracking ==========
    
    override suspend fun trackDevice(data: DeviceTrackingData): Boolean = dbQuery {
        ReviewRiskTrackingTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[entryType] = "device"
            it[deviceFingerprint] = data.deviceFingerprint
            it[userId] = data.userId
            it[ipAddress] = data.ipAddress
            it[userAgent] = data.userAgent
            it[action] = data.action
            it[occurredAt] = data.timestamp
        }.insertedCount > 0
    }
    
    override suspend fun getUsersByDevice(deviceFingerprint: String): List<String> = dbQuery {
        ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "device") and
                        (ReviewRiskTrackingTable.deviceFingerprint eq deviceFingerprint)
            }
            .map { it[ReviewRiskTrackingTable.userId] }
            .distinct()
    }
    
    override suspend fun getDevicesByUser(userId: String): List<String> = dbQuery {
        ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "device") and
                        (ReviewRiskTrackingTable.userId eq userId)
            }
            .mapNotNull { it[ReviewRiskTrackingTable.deviceFingerprint] }
            .distinct()
    }
    
    override suspend fun analyzeDevicePattern(deviceFingerprint: String): DevicePatternAnalysis? = dbQuery {
        val records = ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "device") and
                        (ReviewRiskTrackingTable.deviceFingerprint eq deviceFingerprint)
            }
            .orderBy(ReviewRiskTrackingTable.occurredAt to SortOrder.ASC)
            .toList()

        if (records.isEmpty()) return@dbQuery null

        val associatedUsers = records.map { it[ReviewRiskTrackingTable.userId] }.distinct()
        val firstSeen = records.first()[ReviewRiskTrackingTable.occurredAt]
        val lastSeen = records.last()[ReviewRiskTrackingTable.occurredAt]
        val totalAccounts = associatedUsers.size

        // Suspicious if multiple accounts use same device
        val suspiciousActivity = totalAccounts > 1

        // Extract device info from user agent if available
        val userAgent = records.firstOrNull { it[ReviewRiskTrackingTable.userAgent] != null }
            ?.get(ReviewRiskTrackingTable.userAgent)
        
        DevicePatternAnalysis(
            deviceFingerprint = deviceFingerprint,
            associatedUserIds = associatedUsers,
            firstSeenAt = firstSeen,
            lastSeenAt = lastSeen,
            totalAccounts = totalAccounts,
            suspiciousActivity = suspiciousActivity,
            deviceType = extractDeviceType(userAgent),
            operatingSystem = extractOS(userAgent),
            browser = extractBrowser(userAgent)
        )
    }
    
    override suspend fun shareDevice(user1Id: String, user2Id: String): Boolean = dbQuery {
        val user1Devices = getDevicesByUser(user1Id)
        val user2Devices = getDevicesByUser(user2Id)
        user1Devices.intersect(user2Devices.toSet()).isNotEmpty()
    }
    
    // ========== IP Tracking ==========
    
    override suspend fun trackIp(data: IpTrackingData): Boolean = dbQuery {
        ReviewRiskTrackingTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[entryType] = "ip"
            it[ipAddress] = data.ipAddress
            it[userId] = data.userId
            it[action] = data.action
            it[occurredAt] = data.timestamp
        }.insertedCount > 0
    }
    
    override suspend fun getUsersByIp(ipAddress: String): List<String> = dbQuery {
        ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "ip") and
                        (ReviewRiskTrackingTable.ipAddress eq ipAddress)
            }
            .map { it[ReviewRiskTrackingTable.userId] }
            .distinct()
    }
    
    override suspend fun getIpsByUser(userId: String): List<String> = dbQuery {
        ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "ip") and
                        (ReviewRiskTrackingTable.userId eq userId)
            }
            .mapNotNull { it[ReviewRiskTrackingTable.ipAddress] }
            .distinct()
    }
    
    override suspend fun analyzeIpPattern(ipAddress: String): IpPatternAnalysis? = dbQuery {
        val records = ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "ip") and
                        (ReviewRiskTrackingTable.ipAddress eq ipAddress)
            }
            .orderBy(ReviewRiskTrackingTable.occurredAt to SortOrder.ASC)
            .toList()

        if (records.isEmpty()) return@dbQuery null

        val associatedUsers = records.map { it[ReviewRiskTrackingTable.userId] }.distinct()
        val firstSeen = records.first()[ReviewRiskTrackingTable.occurredAt]
        val lastSeen = records.last()[ReviewRiskTrackingTable.occurredAt]
        val totalAccounts = associatedUsers.size

        val firstRecord = records.first()

        IpPatternAnalysis(
            ipAddress = ipAddress,
            associatedUserIds = associatedUsers,
            firstSeenAt = firstSeen,
            lastSeenAt = lastSeen,
            totalAccounts = totalAccounts,
            isVpn = firstRecord[ReviewRiskTrackingTable.isVpn],
            isProxy = firstRecord[ReviewRiskTrackingTable.isProxy],
            isTor = firstRecord[ReviewRiskTrackingTable.isTor],
            isDataCenter = firstRecord[ReviewRiskTrackingTable.isDataCenter],
            country = firstRecord[ReviewRiskTrackingTable.country],
            city = firstRecord[ReviewRiskTrackingTable.city],
            isp = firstRecord[ReviewRiskTrackingTable.isp],
            suspiciousActivity = totalAccounts > 2 // More than 2 accounts is suspicious
        )
    }
    
    override suspend fun shareIp(user1Id: String, user2Id: String): Boolean = dbQuery {
        val user1Ips = getIpsByUser(user1Id)
        val user2Ips = getIpsByUser(user2Id)
        user1Ips.intersect(user2Ips.toSet()).isNotEmpty()
    }
    
    override suspend fun updateIpMetadata(
        ipAddress: String,
        isVpn: Boolean,
        isProxy: Boolean,
        isTor: Boolean,
        isDataCenter: Boolean,
        country: String?,
        city: String?,
        isp: String?
    ): Boolean = dbQuery {
        ReviewRiskTrackingTable.update({
            (ReviewRiskTrackingTable.entryType eq "ip") and
                    (ReviewRiskTrackingTable.ipAddress eq ipAddress)
        }) {
            it[ReviewRiskTrackingTable.isVpn] = isVpn
            it[ReviewRiskTrackingTable.isProxy] = isProxy
            it[ReviewRiskTrackingTable.isTor] = isTor
            it[ReviewRiskTrackingTable.isDataCenter] = isDataCenter
            if (country != null) it[ReviewRiskTrackingTable.country] = country
            if (city != null) it[ReviewRiskTrackingTable.city] = city
            if (isp != null) it[ReviewRiskTrackingTable.isp] = isp
        } > 0
    }
    
    // ========== Location Change Tracking ==========
    
    override suspend fun trackLocationChange(data: LocationChangeData): Boolean = dbQuery {
        ReviewRiskTrackingTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[entryType] = "location_change"
            it[userId] = data.userId
            // Parse old location if provided
            if (data.oldLatitude != null && data.oldLongitude != null) {
                it[oldLocation] = Point(data.oldLongitude, data.oldLatitude)
                    .also { p -> p.srid = 4326 }
            }
            // Parse new location
            it[newLocation] = Point(data.newLongitude, data.newLatitude)
                .also { p -> p.srid = 4326 }
            it[occurredAt] = data.changedAt
        }.insertedCount > 0
    }
    
    override suspend fun getUserLocationChanges(userId: String, limit: Int): List<LocationChange> = dbQuery {
        ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "location_change") and
                        (ReviewRiskTrackingTable.userId eq userId)
            }
            .orderBy(ReviewRiskTrackingTable.occurredAt to SortOrder.DESC)
            .limit(limit)
            .map {
                val oldLocation = it[ReviewRiskTrackingTable.oldLocation]
                val newLocation = it[ReviewRiskTrackingTable.newLocation]

                LocationChange(
                    id = it[ReviewRiskTrackingTable.id],
                    userId = it[ReviewRiskTrackingTable.userId],
                    oldLatitude = oldLocation?.y, // y is latitude in PostGIS
                    oldLongitude = oldLocation?.x, // x is longitude in PostGIS
                    newLatitude = newLocation?.y ?: 0.0, // newLocation should not be null, but use safe access
                    newLongitude = newLocation?.x ?: 0.0,
                    changedAt = it[ReviewRiskTrackingTable.occurredAt]
                )
            }
    }
    
    override suspend fun analyzeLocationChangePattern(userId: String): LocationChangePatternAnalysis? = dbQuery {
        val changes = getUserLocationChanges(userId, 100)
        
        if (changes.isEmpty()) return@dbQuery null
        
        val suspiciousPatterns = mutableListOf<String>()
        var totalDistance = 0.0
        var maxConsecutiveDistance = 0.0
        var locationHoppingDetected = false
        var impossibleMovementDetected = false
        var frequentChangesDetected = false
        
        // Analyze consecutive location changes
        for (i in 0 until changes.size - 1) {
            val change1 = changes[i]
            val change2 = changes[i + 1]
            
            // Calculate distance between old location and new location of next change
            val lat1 = change1.newLatitude
            val lon1 = change1.newLongitude
            val lat2 = change2.newLatitude
            val lon2 = change2.newLongitude
            
            val distance = calculateHaversineDistance(lat1, lon1, lat2, lon2)
            val timeDiff = abs(change2.changedAt.epochSecond - change1.changedAt.epochSecond)
            val timeDiffHours = timeDiff / 3600.0
            
            totalDistance += distance
            maxConsecutiveDistance = max(maxConsecutiveDistance, distance)
            
            // Check for location hopping (frequent changes)
            if (timeDiffHours < 24 && distance > 50_000) {
                locationHoppingDetected = true
                suspiciousPatterns.add("Location hopping: ${String.format("%.0f", distance / 1000)}" +
                        "km in ${String.format("%.1f", timeDiffHours)}h")
            }
            
            // Check for impossible movement (> 500km in < 6 hours for profile changes)
            if (distance > 500_000 && timeDiffHours < 6) {
                impossibleMovementDetected = true
                suspiciousPatterns.add("Impossible movement: ${String.format("%.0f", distance / 1000)}" +
                        "km in ${String.format("%.1f", timeDiffHours)}h")
            }
        }
        
        val averageDistance = if (changes.size > 1) {
            totalDistance / (changes.size - 1)
        } else null
        
        // Check for frequent changes (more than 5 changes in 30 days)
        if (changes.size > 5) {
            val oldestChange = changes.last().changedAt
            val newestChange = changes.first().changedAt
            val daysDiff = (newestChange.epochSecond - oldestChange.epochSecond) / 86400.0
            
            if (daysDiff < 30) {
                frequentChangesDetected = true
                suspiciousPatterns.add("Frequent changes: ${changes.size} changes in ${String.format("%.0f", daysDiff)} days")
            }
        }
        
        LocationChangePatternAnalysis(
            userId = userId,
            changes = changes,
            suspiciousPatterns = suspiciousPatterns,
            averageDistanceBetweenChanges = averageDistance?.div(1000.0),
            maxDistanceBetweenConsecutiveChanges = maxConsecutiveDistance.div(1000.0),
            locationHoppingDetected = locationHoppingDetected,
            impossibleMovementDetected = impossibleMovementDetected,
            frequentChangesDetected = frequentChangesDetected
        )
    }
    
    override suspend fun calculateLocationDistance(user1Id: String, user2Id: String): Double? {
        // Get current location from user profiles instead of tracking table
        // This requires UserProfileDao, but we'll delegate to the service layer
        // For now, return null as we need to query UserProfilesTable
        return null
    }
    
    override suspend fun getUsersByLocationChange(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        timeRangeMinutes: Int
    ): List<String> = dbQuery {
        val cutoffTime = Instant.now().minusSeconds(timeRangeMinutes * 60L)
        
        // Use PostGIS ST_DWithin for spatial query
        val users = mutableListOf<String>()
        // Note: This requires PostGIS extension and proper spatial queries
        // For now, we'll use a simpler approach and calculate distance manually
        
        ReviewRiskTrackingTable
            .selectAll()
            .where {
                (ReviewRiskTrackingTable.entryType eq "location_change") and
                        (ReviewRiskTrackingTable.occurredAt greaterEq cutoffTime)
            }
            .orderBy(ReviewRiskTrackingTable.occurredAt to SortOrder.DESC)
            .forEach {
                val location = it[ReviewRiskTrackingTable.newLocation] ?: return@forEach

                val locLat = location.y
                val locLon = location.x
                val distance = calculateHaversineDistance(latitude, longitude, locLat, locLon)

                if (distance <= radiusKm * 1000) {
                    users.add(it[ReviewRiskTrackingTable.userId])
                }
            }
        
        users.distinct()
    }
    
    override suspend fun detectCoordinatedLocationChanges(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        timeWindowMinutes: Int,
        thresholdUsers: Int
    ): List<String> {
        val suspectUsers = getUsersByLocationChange(latitude, longitude, radiusKm, timeWindowMinutes)
        
        // If more than threshold users changed to this location, flag as coordinated
        return if (suspectUsers.size >= thresholdUsers) {
            suspectUsers
        } else {
            emptyList()
        }
    }
    
    // ========== Risk Pattern Detection ==========
    
    override suspend fun recordSuspiciousPattern(pattern: SuspiciousPattern): Boolean = dbQuery {
        RiskPatternsTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[patternType] = pattern.type.name
            it[severity] = pattern.severity.name
            it[description] = pattern.description
            it[affectedUsers] = pattern.affectedUsers
            it[evidence] = pattern.evidence
            it[detectedAt] = Instant.now()
            it[status] = "pending"
        }.insertedCount > 0
    }
    
    override suspend fun getActiveSuspiciousPatterns(): List<SuspiciousPattern> = dbQuery {
        RiskPatternsTable
            .selectAll()
            .where { RiskPatternsTable.status eq "pending" }
            .orderBy(RiskPatternsTable.detectedAt to SortOrder.DESC)
            .map { rowToSuspiciousPattern(it) }
    }
    
    override suspend fun getUserSuspiciousPatterns(userId: String): List<SuspiciousPattern> = dbQuery {
        // This is a simplified query - in production, you'd parse the JSON array
        RiskPatternsTable
            .selectAll()
            .orderBy(RiskPatternsTable.detectedAt to SortOrder.DESC)
            .map { rowToSuspiciousPattern(it) }
            .filter { userId in it.affectedUsers }
    }
    
    override suspend fun updatePatternStatus(
        patternId: String,
        status: String,
        reviewedBy: String?,
        notes: String?
    ): Boolean = dbQuery {
        RiskPatternsTable.update({ RiskPatternsTable.id eq patternId }) {
            it[RiskPatternsTable.status] = status
            if (reviewedBy != null) it[RiskPatternsTable.reviewedBy] = reviewedBy
            if (notes != null) it[RiskPatternsTable.notes] = notes
            if (status == "resolved") it[resolvedAt] = Instant.now()
        } > 0
    }
    
    // ========== Behavior Analysis ==========
    
    override suspend fun getBehaviorMetrics(userId: String): BehaviorAnalysisMetrics = dbQuery {
        // This would typically query multiple tables
        // For now, return a basic implementation
        val devices = getDevicesByUser(userId)
        val ips = getIpsByUser(userId)
        
        val suspiciousFlags = mutableListOf<String>()
        if (devices.size > 3) suspiciousFlags.add("multiple_devices")
        if (ips.size > 10) suspiciousFlags.add("multiple_ips")
        
        BehaviorAnalysisMetrics(
            userId = userId,
            accountAge = 0, // Would query from user profile
            totalTransactions = 0,
            completedTransactions = 0,
            cancelledTransactions = 0,
            averageReviewsReceived = 0.0,
            diversityScore = 0.5,
            suspiciousBehaviorFlags = suspiciousFlags
        )
    }
    
    override suspend fun shareContactInfo(user1Id: String, user2Id: String): Boolean {
        // This would query user profiles for contact information
        // For now, return false as a safe default
        return false
    }
    
    // ========== Helper Methods ==========
    
    private fun rowToSuspiciousPattern(row: ResultRow): SuspiciousPattern {
        return SuspiciousPattern(
            type = PatternType.valueOf(row[RiskPatternsTable.patternType]),
            description = row[RiskPatternsTable.description],
            severity = PatternSeverity.valueOf(row[RiskPatternsTable.severity]),
            affectedUsers = row[RiskPatternsTable.affectedUsers],
            evidence = row[RiskPatternsTable.evidence]
        )
    }
    
    /**
     * Calculates distance between two GPS coordinates using Haversine formula.
     * Returns distance in meters.
     */
    private fun calculateHaversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return r * c
    }
    
    private fun extractDeviceType(userAgent: String?): String? {
        if (userAgent == null) return null
        return when {
            userAgent.contains("Mobile", ignoreCase = true) -> "mobile"
            userAgent.contains("Tablet", ignoreCase = true) -> "tablet"
            else -> "desktop"
        }
    }
    
    private fun extractOS(userAgent: String?): String? {
        if (userAgent == null) return null
        return when {
            userAgent.contains("Windows") -> "Windows"
            userAgent.contains("Mac OS") -> "macOS"
            userAgent.contains("Android") -> "Android"
            userAgent.contains("iOS") || userAgent.contains("iPhone") -> "iOS"
            userAgent.contains("Linux") -> "Linux"
            else -> null
        }
    }
    
    private fun extractBrowser(userAgent: String?): String? {
        if (userAgent == null) return null
        return when {
            userAgent.contains("Chrome") && !userAgent.contains("Edg") -> "Chrome"
            userAgent.contains("Safari") && !userAgent.contains("Chrome") -> "Safari"
            userAgent.contains("Firefox") -> "Firefox"
            userAgent.contains("Edg") -> "Edge"
            else -> null
        }
    }
    
    // ========== Data Cleanup & Maintenance ==========
    
    override suspend fun cleanupOldDeviceTracking(olderThanDays: Int, excludedUserIds: Set<String>): Int = dbQuery {
        try {
            val cutoffDate = Instant.now()
                .minus(java.time.Duration.ofDays(olderThanDays.toLong()))
            
            ReviewRiskTrackingTable.deleteWhere {
                val baseFilter = (ReviewRiskTrackingTable.entryType eq "device") and
                        (ReviewRiskTrackingTable.occurredAt less cutoffDate)
                if (excludedUserIds.isEmpty()) {
                    baseFilter
                } else {
                    baseFilter and (ReviewRiskTrackingTable.userId notInList excludedUserIds.toList())
                }
            }
        } catch (e: Exception) {
            log.error("Error cleaning up device tracking", e)
            e.printStackTrace()
            0
        }
    }
    
    override suspend fun cleanupOldIpTracking(olderThanDays: Int, excludedUserIds: Set<String>): Int = dbQuery {
        try {
            val cutoffDate = Instant.now()
                .minus(java.time.Duration.ofDays(olderThanDays.toLong()))
            
            ReviewRiskTrackingTable.deleteWhere {
                val baseFilter = (ReviewRiskTrackingTable.entryType eq "ip") and
                        (ReviewRiskTrackingTable.occurredAt less cutoffDate)
                if (excludedUserIds.isEmpty()) {
                    baseFilter
                } else {
                    baseFilter and (ReviewRiskTrackingTable.userId notInList excludedUserIds.toList())
                }
            }
        } catch (e: Exception) {
            log.error("Error cleaning up IP tracking", e)
            e.printStackTrace()
            0
        }
    }
    
    override suspend fun cleanupOldLocationChanges(olderThanDays: Int, excludedUserIds: Set<String>): Int = dbQuery {
        try {
            val cutoffDate = Instant.now()
                .minus(java.time.Duration.ofDays(olderThanDays.toLong()))
            
            ReviewRiskTrackingTable.deleteWhere {
                val baseFilter = (ReviewRiskTrackingTable.entryType eq "location_change") and
                        (ReviewRiskTrackingTable.occurredAt less cutoffDate)
                if (excludedUserIds.isEmpty()) {
                    baseFilter
                } else {
                    baseFilter and (ReviewRiskTrackingTable.userId notInList excludedUserIds.toList())
                }
            }
        } catch (e: Exception) {
            log.error("Error cleaning up location changes", e)
            e.printStackTrace()
            0
        }
    }
    
    override suspend fun cleanupOldRiskPatterns(olderThanDays: Int, excludedUserIds: Set<String>): Int = dbQuery {
        try {
            val cutoffDate = Instant.now()
                .minus(java.time.Duration.ofDays(olderThanDays.toLong()))
            
            RiskPatternsTable.deleteWhere {
                RiskPatternsTable.detectedAt less cutoffDate
            }
        } catch (e: Exception) {
            log.error("Error cleaning up risk patterns", e)
            e.printStackTrace()
            0
        }
    }
}
