package app.bartering.features.authentication.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.model.DeleteUserRequest
import app.bartering.features.authentication.model.DeleteUserResponse
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.compliance.service.ComplianceAuditService
import app.bartering.features.compliance.service.LegalHoldService
import app.bartering.utils.HashUtils
import org.koin.java.KoinJavaComponent.inject

/**
 * Route for deleting a user and all their associated data.
 * 
 * Requires signature verification to ensure the request comes from the user being deleted.
 * 
 * This endpoint will delete:
 * - User registration data
 * - User profile (cascades via FK)
 * - User attributes (cascades via FK)
 * - User relationships (cascades via FK)
 * - User postings (cascades via FK)
 *   - Posting attributes links (cascades via FK from postings)
 * - Offline messages (handled via sender_id/recipient_id matching)
 * - Encrypted files (handled via sender_id/recipient_id matching)
 */
fun Route.deleteUserRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val legalHoldService: LegalHoldService by inject(LegalHoldService::class.java)
    val complianceAuditService: ComplianceAuditService by inject(ComplianceAuditService::class.java)

    delete("/api/v1/authentication/user/{userId}") {
        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            // Error response has already been sent by verifyRequestSignature
            return@delete
        }

        // Get the userId from the path parameter
        val userIdToDelete = call.parameters["userId"]

        if (userIdToDelete.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                DeleteUserResponse(
                    success = false,
                    message = "User ID is required"
                )
            )
            return@delete
        }

        // Verify that the authenticated user matches the user being deleted
        if (authenticatedUserId != userIdToDelete) {
            call.respond(
                HttpStatusCode.Forbidden,
                DeleteUserResponse(
                    success = false,
                    message = "You are not authorized to delete this user account"
                )
            )
            return@delete
        }

        try {
            // Parse the request body for additional confirmation
            val deleteRequest = if (requestBody.isNotBlank() && requestBody != "{}") {
                try {
                    Json.decodeFromString<DeleteUserRequest>(requestBody)
                } catch (_: Exception) {
                    // If parsing fails, create a default request
                    DeleteUserRequest(userIdToDelete, true)
                }
            } else {
                DeleteUserRequest(userIdToDelete, true)
            }

            // Verify userId in body matches the path parameter
            if (deleteRequest.userId != userIdToDelete) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    DeleteUserResponse(
                        success = false,
                        message = "User ID in request body does not match path parameter"
                    )
                )
                return@delete
            }

            kotlinx.coroutines.runBlocking {
                complianceAuditService.logEvent(
                    actorType = "user",
                    actorId = authenticatedUserId,
                    eventType = "ACCOUNT_DELETION_REQUESTED",
                    entityType = "user_account",
                    entityId = userIdToDelete,
                    purpose = "gdpr_right_to_erasure",
                    outcome = "success",
                    requestId = call.request.headers["X-Request-ID"],
                    ipHash = (call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                        ?: call.request.headers["X-Real-IP"])?.let { HashUtils.sha256(it) },
                    deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) }
                )
            }

            val hasDeletionHold = legalHoldService.hasActiveHold(userIdToDelete, "deletion")
            if (hasDeletionHold) {
                kotlinx.coroutines.runBlocking {
                    complianceAuditService.logEvent(
                        actorType = "user",
                        actorId = authenticatedUserId,
                        eventType = "ACCOUNT_DELETION_BLOCKED_BY_LEGAL_HOLD",
                        entityType = "user_account",
                        entityId = userIdToDelete,
                        purpose = "legal_hold_enforcement",
                        outcome = "denied",
                        requestId = call.request.headers["X-Request-ID"],
                        ipHash = (call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                            ?: call.request.headers["X-Real-IP"])?.let { HashUtils.sha256(it) },
                        deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) }
                    )
                }

                call.respond(
                    HttpStatusCode.Conflict,
                    DeleteUserResponse(
                        success = false,
                        message = "Account deletion is temporarily blocked due to an active legal hold"
                    )
                )
                return@delete
            }

            // Perform the deletion
            val deleted = authDao.deleteUserAndAllData(userIdToDelete)

            if (deleted) {
                kotlinx.coroutines.runBlocking {
                    complianceAuditService.logEvent(
                        actorType = "user",
                        actorId = authenticatedUserId,
                        eventType = "ACCOUNT_DELETION_COMPLETED",
                        entityType = "user_account",
                        entityId = userIdToDelete,
                        purpose = "gdpr_right_to_erasure",
                        outcome = "success",
                        requestId = call.request.headers["X-Request-ID"],
                        ipHash = (call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                            ?: call.request.headers["X-Real-IP"])?.let { HashUtils.sha256(it) },
                        deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) }
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    DeleteUserResponse(
                        success = true,
                        message = "User account and all associated data have been permanently deleted"
                    )
                )
            } else {
                kotlinx.coroutines.runBlocking {
                    complianceAuditService.logEvent(
                        actorType = "user",
                        actorId = authenticatedUserId,
                        eventType = "ACCOUNT_DELETION_COMPLETED",
                        entityType = "user_account",
                        entityId = userIdToDelete,
                        purpose = "gdpr_right_to_erasure",
                        outcome = "error",
                        requestId = call.request.headers["X-Request-ID"],
                        ipHash = (call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                            ?: call.request.headers["X-Real-IP"])?.let { HashUtils.sha256(it) },
                        deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) },
                        details = mapOf("reason" to "user_not_found_or_already_deleted")
                    )
                }

                call.respond(
                    HttpStatusCode.NotFound,
                    DeleteUserResponse(
                        success = false,
                        message = "User not found or already deleted"
                    )
                )
            }
        } catch (e: Exception) {
            kotlinx.coroutines.runBlocking {
                complianceAuditService.logEvent(
                    actorType = "user",
                    actorId = authenticatedUserId,
                    eventType = "ACCOUNT_DELETION_COMPLETED",
                    entityType = "user_account",
                    entityId = userIdToDelete,
                    purpose = "gdpr_right_to_erasure",
                    outcome = "error",
                    requestId = call.request.headers["X-Request-ID"],
                    ipHash = (call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                        ?: call.request.headers["X-Real-IP"])?.let { HashUtils.sha256(it) },
                    deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) },
                    details = mapOf("error" to (e.message ?: "unknown_error"))
                )
            }

            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                DeleteUserResponse(
                    success = false,
                    message = "An error occurred while deleting the user: ${e.message}"
                )
            )
        }
    }
}

/**
 * Extension function to register all authentication routes
 */
fun Application.authenticationRoutes() {
    routing {
        rateLimit(RateLimitName("authentication")) {
            deleteUserRoute()
        }
    }
}
