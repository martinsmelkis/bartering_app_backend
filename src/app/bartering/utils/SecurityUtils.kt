package app.bartering.utils

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * Security utilities for input validation and SQL injection prevention
 */
object SecurityUtils {

    /**
     * Sanitizes a string for safe use in SQL queries by escaping single quotes
     * NOTE: This should only be used as a last resort. Prefer parameterized queries.
     */
    fun sanitizeSqlString(input: String): String {
        return input.replace("'", "''")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Validates that a string contains only alphanumeric characters, underscores, and hyphens
     * Useful for validating identifiers like user IDs, attribute keys, etc.
     */
    fun isValidIdentifier(input: String, maxLength: Int = 255): Boolean {
        if (input.isBlank() || input.length > maxLength) return false
        return input.matches(Regex("^[a-zA-Z0-9_-]+$"))
    }

    /**
     * Validates UUID format
     */
    fun isValidUUID(input: String): Boolean {
        return input.matches(
            Regex(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                RegexOption.IGNORE_CASE
            )
        )
    }

    /**
     * Validates email format (basic validation)
     */
    fun isValidEmail(input: String): Boolean {
        if (input.length > 255) return false
        return input.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
    }

    /**
     * Validates string length
     */
    fun isValidLength(input: String, minLength: Int = 1, maxLength: Int = 10000): Boolean {
        return input.length in minLength..maxLength
    }

    /**
     * Escapes SQL LIKE pattern special characters
     */
    fun escapeSqlLikePattern(input: String): String {
        return input.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
            .replace("[", "\\[")
    }

    /**
     * Generic error response without exposing internal details
     */
    suspend fun respondWithGenericError(
        call: ApplicationCall,
        statusCode: HttpStatusCode = HttpStatusCode.InternalServerError
    ) {
        val message = when (statusCode) {
            HttpStatusCode.BadRequest -> "Invalid request"
            HttpStatusCode.Unauthorized -> "Authentication failed"
            HttpStatusCode.Forbidden -> "Access denied"
            HttpStatusCode.NotFound -> "Resource not found"
            else -> "An error occurred"
        }
        call.respond(statusCode, mapOf("error" to message))
    }

    /**
     * Validates numeric input within range
     */
    fun isValidNumber(
        value: Double,
        min: Double = Double.MIN_VALUE,
        max: Double = Double.MAX_VALUE
    ): Boolean {
        return value.isFinite() && value in min..max
    }

    /**
     * Validates that a map contains only valid keys and values
     */
    fun isValidMap(map: Map<String, Double>, maxSize: Int = 100): Boolean {
        if (map.size > maxSize) return false
        return map.all { (key, value) ->
            key.length <= 1000 && value.isFinite()
        }
    }

    /**
     * Strips potentially dangerous SQL keywords from input
     * This is a defense-in-depth measure, NOT a replacement for parameterized queries
     */
    fun containsSqlInjectionPatterns(input: String): Boolean {
        val dangerousPatterns = listOf(
            "(?i).*\\b(DROP|DELETE|INSERT|UPDATE|ALTER|CREATE|TRUNCATE|EXEC|EXECUTE)\\b.*",
            "(?i).*\\b(UNION|SELECT)\\b.*\\b(FROM|WHERE)\\b.*",
            "(?i).*--;.*",
            "(?i).*\\/\\*.*\\*\\/.*",
            "(?i).*\\bOR\\b.*=.*",
            "(?i).*\\bAND\\b.*=.*"
        )
        return dangerousPatterns.any { input.matches(Regex(it)) }
    }
}
