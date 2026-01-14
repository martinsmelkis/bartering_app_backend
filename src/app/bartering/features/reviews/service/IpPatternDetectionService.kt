package app.bartering.features.reviews.service

import app.bartering.features.reviews.dao.RiskPatternDao
import app.bartering.features.reviews.model.*
import java.time.Instant

/**
 * Service for detecting suspicious IP address patterns.
 * Identifies VPN/proxy usage, IP sharing, and coordinated attacks.
 */
class IpPatternDetectionService(
    private val riskPatternDao: RiskPatternDao
) {
    
    /**
     * Analyzes IP usage patterns for a user.
     */
    suspend fun analyzeUserIps(userId: String): List<SuspiciousPattern> {
        val patterns = mutableListOf<SuspiciousPattern>()
        val ips = riskPatternDao.getIpsByUser(userId)
        
        // Check for excessive IP switching (possible VPN/proxy abuse)
        if (ips.size > 20) {
            patterns.add(
                SuspiciousPattern(
                    type = PatternType.VPN_ABUSE,
                    description = "User has used ${ips.size} different IP addresses",
                    severity = when {
                        ips.size > 50 -> PatternSeverity.HIGH
                        ips.size > 30 -> PatternSeverity.MEDIUM
                        else -> PatternSeverity.LOW
                    },
                    affectedUsers = listOf(userId),
                    evidence = mapOf(
                        "ip_count" to ips.size.toString(),
                        "reason" to "Excessive IP switching indicates VPN/proxy usage"
                    )
                )
            )
        }
        
        // Check each IP for multi-user usage
        for (ip in ips) {
            val analysis = riskPatternDao.analyzeIpPattern(ip) ?: continue
            
            // VPN/Proxy detection
            if (analysis.isVpn || analysis.isProxy || analysis.isTor) {
                patterns.add(
                    SuspiciousPattern(
                        type = PatternType.VPN_ABUSE,
                        description = buildString {
                            append("IP ${ip} detected as ")
                            when {
                                analysis.isTor -> append("Tor exit node")
                                analysis.isProxy -> append("proxy server")
                                analysis.isVpn -> append("VPN")
                            }
                        },
                        severity = when {
                            analysis.isTor -> PatternSeverity.HIGH
                            analysis.isProxy -> PatternSeverity.MEDIUM
                            analysis.isVpn -> PatternSeverity.LOW
                            else -> PatternSeverity.INFO
                        },
                        affectedUsers = analysis.associatedUserIds,
                        evidence = mapOf(
                            "ip_address" to ip,
                            "is_vpn" to analysis.isVpn.toString(),
                            "is_proxy" to analysis.isProxy.toString(),
                            "is_tor" to analysis.isTor.toString(),
                            "is_datacenter" to analysis.isDataCenter.toString(),
                            "country" to (analysis.country ?: "unknown"),
                            "isp" to (analysis.isp ?: "unknown")
                        )
                    )
                )
            }
            
            // Multi-account from same IP
            if (analysis.suspiciousActivity && analysis.totalAccounts > 2) {
                patterns.add(
                    SuspiciousPattern(
                        type = PatternType.IP_SHARING,
                        description = "IP ${ip} used by ${analysis.totalAccounts} accounts",
                        severity = when {
                            analysis.totalAccounts > 10 -> PatternSeverity.CRITICAL
                            analysis.totalAccounts > 5 -> PatternSeverity.HIGH
                            else -> PatternSeverity.MEDIUM
                        },
                        affectedUsers = analysis.associatedUserIds,
                        evidence = mapOf(
                            "ip_address" to ip,
                            "account_count" to analysis.totalAccounts.toString(),
                            "country" to (analysis.country ?: "unknown"),
                            "city" to (analysis.city ?: "unknown"),
                            "isp" to (analysis.isp ?: "unknown")
                        )
                    )
                )
            }
        }
        
        return patterns
    }
    
    /**
     * Checks if two users share an IP address.
     */
    suspend fun checkIpSharing(user1Id: String, user2Id: String): SuspiciousPattern? {
        val shareIp = riskPatternDao.shareIp(user1Id, user2Id)
        
        if (!shareIp) return null
        
        val sharedIps = findSharedIps(user1Id, user2Id)
        
        return SuspiciousPattern(
            type = PatternType.IP_SHARING,
            description = "Users share ${sharedIps.size} IP address(es)",
            severity = when {
                sharedIps.size > 5 -> PatternSeverity.CRITICAL
                sharedIps.size > 2 -> PatternSeverity.HIGH
                else -> PatternSeverity.MEDIUM
            },
            affectedUsers = listOf(user1Id, user2Id),
            evidence = mapOf(
                "shared_ips" to sharedIps.joinToString(","),
                "ip_count" to sharedIps.size.toString(),
                "risk" to "Potential coordinated activity"
            )
        )
    }
    
    /**
     * Tracks an IP usage event.
     */
    suspend fun trackIpUsage(
        userId: String,
        ipAddress: String,
        action: String
    ) {
        riskPatternDao.trackIp(
            IpTrackingData(
                ipAddress = ipAddress,
                userId = userId,
                timestamp = Instant.now(),
                action = action
            )
        )
        
        // Optionally enrich IP data with geolocation/VPN detection
        // This would typically call an external API like IPHub, IPQualityScore, etc.
        enrichIpData(ipAddress)
    }
    
    /**
     * Enriches IP data with geolocation and proxy/VPN detection.
     * In production, this would call an external API.
     */
    private suspend fun enrichIpData(ipAddress: String) {
        // Check if we already have data for this IP
        val existing = riskPatternDao.analyzeIpPattern(ipAddress)
        if (existing != null && existing.country != null) {
            return // Already enriched
        }
        
        // In production, call external API like:
        // - IPHub.info
        // - IPQualityScore
        // - MaxMind GeoIP2
        // - IPData.co
        
        // For now, implement basic detection heuristics
        val isVpn = detectVpnHeuristic(ipAddress)
        val isProxy = detectProxyHeuristic(ipAddress)
        val isTor = detectTorHeuristic(ipAddress)
        val isDataCenter = detectDataCenterHeuristic(ipAddress)
        
        riskPatternDao.updateIpMetadata(
            ipAddress = ipAddress,
            isVpn = isVpn,
            isProxy = isProxy,
            isTor = isTor,
            isDataCenter = isDataCenter,
            country = null, // Would come from API
            city = null,
            isp = null
        )
    }
    
    /**
     * Calculates IP risk score for a user.
     */
    suspend fun calculateIpRiskScore(userId: String): Double {
        val patterns = analyzeUserIps(userId)
        
        if (patterns.isEmpty()) return 0.0
        
        // Weight patterns by severity
        val severityWeights = mapOf(
            PatternSeverity.INFO to 0.05,
            PatternSeverity.LOW to 0.15,
            PatternSeverity.MEDIUM to 0.35,
            PatternSeverity.HIGH to 0.65,
            PatternSeverity.CRITICAL to 0.95
        )
        
        val totalWeight = patterns.sumOf { severityWeights[it.severity] ?: 0.0 }
        
        // Normalize to 0-1 range
        return (totalWeight / patterns.size).coerceIn(0.0, 1.0)
    }
    
    /**
     * Detects coordinated review campaigns from same IP.
     */
    suspend fun detectCoordinatedActivity(ipAddress: String): SuspiciousPattern? {
        val analysis = riskPatternDao.analyzeIpPattern(ipAddress) ?: return null
        
        if (analysis.totalAccounts < 3) return null
        
        return SuspiciousPattern(
            type = PatternType.COORDINATED_REVIEWS,
            description = "Multiple accounts (${analysis.totalAccounts}) operating from IP ${ipAddress}",
            severity = when {
                analysis.totalAccounts > 10 -> PatternSeverity.CRITICAL
                analysis.totalAccounts > 5 -> PatternSeverity.HIGH
                else -> PatternSeverity.MEDIUM
            },
            affectedUsers = analysis.associatedUserIds,
            evidence = mapOf(
                "ip_address" to ipAddress,
                "account_count" to analysis.totalAccounts.toString(),
                "is_vpn" to analysis.isVpn.toString(),
                "is_proxy" to analysis.isProxy.toString()
            )
        )
    }
    
    /**
     * Finds shared IPs between two users.
     */
    private suspend fun findSharedIps(user1Id: String, user2Id: String): List<String> {
        val user1Ips = riskPatternDao.getIpsByUser(user1Id)
        val user2Ips = riskPatternDao.getIpsByUser(user2Id)
        return user1Ips.intersect(user2Ips.toSet()).toList()
    }
    
    // ========== Heuristic Detection Methods ==========
    // In production, replace these with actual API calls
    
    private fun detectVpnHeuristic(ipAddress: String): Boolean {
        // Basic heuristic - in production use API
        // Common VPN IP ranges, known VPN providers, etc.
        return false
    }
    
    private fun detectProxyHeuristic(ipAddress: String): Boolean {
        // Check against known proxy lists
        return false
    }
    
    private fun detectTorHeuristic(ipAddress: String): Boolean {
        // Check against Tor exit node list
        // https://check.torproject.org/exit-addresses
        return false
    }
    
    private fun detectDataCenterHeuristic(ipAddress: String): Boolean {
        // Check if IP belongs to known data center ranges
        // AWS, GCP, Azure, DigitalOcean, etc.
        return false
    }
}
