package org.barter.middleware

import io.ktor.server.application.*
import io.ktor.server.request.*
import org.barter.features.profile.cache.UserActivityCache

/**
 * Middleware that automatically tracks user activity for presence detection.
 * 
 * This interceptor runs on every API request and updates the user's last activity
 * timestamp if they are authenticated (have X-User-ID header).
 * 
 * Activity types are inferred from the request path:
 * - /nearby -> "browsing"
 * - /similar, /helpful -> "matching"
 * - /search -> "searching"
 * - /chat -> "chatting"
 * - /postings -> "posting"
 * - /reputation -> "reviewing"
 * - default -> "active"
 * 
 * Usage in Application.kt:
 * ```
 * fun Application.module() {
 *     installActivityTracking()
 *     // ... rest of configuration
 * }
 * ```
 */
fun Application.installActivityTracking() {
    println("🔧 Installing Activity Tracking Middleware...")
    
    intercept(ApplicationCallPipeline.Monitoring) {
        // Get authenticated user ID from request headers
        val userId = call.request.headers["X-User-ID"]
        
        if (!userId.isNullOrBlank()) {
            try {
                // Determine activity type from request path
                val path = call.request.path()
                val activityType = determineActivityType(path)
                
                // Update activity in cache (extremely fast, non-blocking)
                UserActivityCache.updateActivity(userId, activityType)
                
            } catch (e: Exception) {
                // Silent fail - activity tracking should never break requests
                // Only log in development/debug mode
                // println("⚠️ Activity tracking error for user $userId: ${e.message}")
            }
        }
        
        // Continue with the request
        proceed()
    }
    
    println("✅ Activity Tracking Middleware installed")
}

/**
 * Determine the type of activity based on the request path.
 * This helps with analytics and understanding user behavior.
 */
private fun determineActivityType(path: String): String {
    return when {
        path.contains("/nearby") -> "browsing"
        path.contains("/similar") -> "matching"
        path.contains("/helpful") -> "matching"
        path.contains("/search") -> "searching"
        path.contains("/chat") -> "chatting"
        path.contains("/postings") && path.contains("POST") -> "posting"
        path.contains("/postings") -> "browsing_postings"
        path.contains("/reputation") -> "reviewing"
        path.contains("/profile") -> "editing_profile"
        path.contains("/reviews") -> "reviewing"
        path.contains("/transactions") -> "trading"
        path.contains("/attributes") -> "editing_attributes"
        path.contains("/relationships") -> "networking"
        else -> "active"
    }
}
