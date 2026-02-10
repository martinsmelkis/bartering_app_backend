package app.bartering.config

import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Industry-standard rate limiting configuration for application-level protection.
 * 
 * This provides a second layer of defense beyond nginx rate limiting,
 * with granular control over specific API operations.
 * 
 * OWASP Recommendations:
 * - Authentication: 3-5 requests per minute
 * - File uploads: 1-3 requests per minute  
 * - Expensive queries: 10-20 requests per minute
 * - General API: 100-300 requests per minute
 * 
 * Note: These limits work in conjunction with nginx limits.
 * Nginx provides first-line defense, Ktor provides fine-grained control.
 */
fun Application.configureRateLimiting() {
    install(RateLimit) {
        
        // ====================================================================
        // GLOBAL RATE LIMIT (Default for all endpoints)
        // ====================================================================
        // Prevents single client from overwhelming the server
        // Industry standard: 100-300 requests per minute
        
        global {
            rateLimiter(limit = 200, refillPeriod = 60.seconds)
        }
        
        // ====================================================================
        // AUTHENTICATION ENDPOINTS - STRICTEST LIMITS
        // ====================================================================
        // Protects against brute force attacks on credentials
        // Industry standard: 3-5 attempts per minute (OWASP recommendation)
        
        register(RateLimitName("authentication")) {
            rateLimiter(limit = 5, refillPeriod = 60.seconds)
            requestKey { call ->
                // Rate limit by IP address
                val ip = call.request.origin.remoteAddress
                "auth:$ip"
            }
        }
        
        // ====================================================================
        // FILE UPLOAD ENDPOINTS - VERY STRICT LIMITS
        // ====================================================================
        // Prevents resource exhaustion from large uploads
        // Industry standard: 1-3 uploads per minute per user
        
        register(RateLimitName("file_upload")) {
            rateLimiter(limit = 3, refillPeriod = 60.seconds)
            requestKey { call ->
                // Rate limit by user ID if available, otherwise IP
                val userId = call.request.headers["X-User-ID"]
                val ip = call.request.origin.remoteAddress
                "upload:${userId ?: ip}"
            }
        }
        
        // ====================================================================
        // EXPENSIVE DATABASE QUERIES - MODERATE LIMITS
        // ====================================================================
        // Semantic search, vector similarity, complex joins, nearby searches
        // Industry standard: 30-60 per minute per user (increased from 10-20 for better UX)
        
        register(RateLimitName("expensive_query")) {
            rateLimiter(limit = 45, refillPeriod = 60.seconds)
            requestKey { call ->
                val userId = call.request.headers["X-User-ID"]
                val ip = call.request.origin.remoteAddress
                "query:${userId ?: ip}"
            }
        }
        
        // ====================================================================
        // PROFILE UPDATES - MODERATE LIMITS
        // ====================================================================
        // Prevents spam and rapid-fire updates
        // Industry standard: 20-30 per minute per user
        
        register(RateLimitName("profile_update")) {
            rateLimiter(limit = 25, refillPeriod = 60.seconds)
            requestKey { call ->
                val userId = call.request.headers["X-User-ID"]
                val ip = call.request.origin.remoteAddress
                "profile:${userId ?: ip}"
            }
        }
        
        // ====================================================================
        // POSTING CREATION - MODERATE LIMITS
        // ====================================================================
        // Prevents spam postings
        // Industry standard: 10-20 posts per hour per user
        
        register(RateLimitName("posting_creation")) {
            rateLimiter(limit = 20, refillPeriod = 60.minutes)
            requestKey { call ->
                val userId = call.request.headers["X-User-ID"]
                "posting:${userId ?: "anonymous"}"
            }
        }
        
        // ====================================================================
        // CHAT MESSAGES - PER-USER LIMITS
        // ====================================================================
        // Prevents chat spam and message flooding
        // Industry standard: 30-60 messages per minute per user
        
        register(RateLimitName("chat_messages")) {
            rateLimiter(limit = 50, refillPeriod = 60.seconds)
            requestKey { call ->
                val userId = call.request.headers["X-User-ID"]
                "chat:${userId ?: "anonymous"}"
            }
        }
        
        // ====================================================================
        // NOTIFICATION PREFERENCES - LENIENT LIMITS
        // ====================================================================
        // Users may need to update multiple preferences
        // Industry standard: 30-50 per minute per user
        
        register(RateLimitName("notification_prefs")) {
            rateLimiter(limit = 40, refillPeriod = 60.seconds)
            requestKey { call ->
                val userId = call.request.headers["X-User-ID"]
                "notif:${userId ?: "anonymous"}"
            }
        }
        
        // ====================================================================
        // PASSWORD RESET - VERY STRICT LIMITS
        // ====================================================================
        // Prevents password reset spam and enumeration attacks
        // Industry standard: 1-3 per hour per email/user
        
        register(RateLimitName("password_reset")) {
            rateLimiter(limit = 3, refillPeriod = 60.minutes)
            requestKey { call ->
                val ip = call.request.origin.remoteAddress
                "reset:$ip"
            }
        }
        
        // ====================================================================
        // EMAIL SENDING - VERY STRICT LIMITS
        // ====================================================================
        // Prevents email spam through the application
        // Industry standard: 5-10 per hour per user
        
        register(RateLimitName("email_send")) {
            rateLimiter(limit = 10, refillPeriod = 60.minutes)
            requestKey { call ->
                val userId = call.request.headers["X-User-ID"]
                "email:${userId ?: "anonymous"}"
            }
        }
    }
}

/**
 * Rate limit names as constants for easy reference in routes.
 */
object RateLimitNames {
    const val AUTHENTICATION = "authentication"
    const val FILE_UPLOAD = "file_upload"
    const val EXPENSIVE_QUERY = "expensive_query"
    const val PROFILE_UPDATE = "profile_update"
    const val POSTING_CREATION = "posting_creation"
    const val CHAT_MESSAGES = "chat_messages"
    const val NOTIFICATION_PREFS = "notification_prefs"
    const val PASSWORD_RESET = "password_reset"
    const val EMAIL_SEND = "email_send"
}
