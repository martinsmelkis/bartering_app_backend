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
import app.bartering.features.compliance.service.DataSubjectRequestService
import app.bartering.features.compliance.service.ErasureTaskService
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
    val dsrService: DataSubjectRequestService by inject(DataSubjectRequestService::class.java)
    val erasureTaskService: ErasureTaskService by inject(ErasureTaskService::class.java)

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

        var dsrRequestId: Long? = null

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

            val requestId = kotlinx.coroutines.runBlocking {
                dsrService.createRequest(
                    userId = userIdToDelete,
                    requestType = "deletion",
                    requestedBy = authenticatedUserId,
                    requestSource = "user",
                    reason = "gdpr_right_to_erasure"
                )
            }
            dsrRequestId = requestId

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
                    deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) },
                    dsrRequestId = requestId
                )
            }

            val hasDeletionHold = legalHoldService.hasActiveHold(userIdToDelete, "deletion")
            if (hasDeletionHold) {
                dsrService.updateStatus(
                    requestId = requestId,
                    status = "rejected",
                    handledBy = "system",
                    rejectionReason = "blocked_by_legal_hold",
                    notes = "Account deletion blocked due to active legal hold"
                )
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
                        deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) },
                        dsrRequestId = requestId
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

                dsrService.updateStatus(
                    requestId = requestId,
                    status = "in_progress",
                    handledBy = "system",
                    notes = "Account deletion processing started"
                )

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
                        deviceIdHash = call.request.headers["X-Device-ID"]?.let { HashUtils.sha256(it) },
                        dsrRequestId = requestId
                    )
                }

                dsrService.completeRequest(
                    requestId = requestId,
                    handledBy = "system",
                    notes = "Account deletion completed successfully"
                )

                val erasureTaskIds = erasureTaskService.createDefaultErasureTasks(
                    userId = userIdToDelete,
                    requestedBy = authenticatedUserId
                )

                complianceAuditService.logEvent(
                    actorType = "system",
                    actorId = authenticatedUserId,
                    eventType = "ERASURE_TASKS_REGISTERED",
                    entityType = "compliance_erasure_task",
                    entityId = userIdToDelete,
                    purpose = "gdpr_right_to_erasure",
                    outcome = "success",
                    requestId = call.request.headers["X-Request-ID"],
                    dsrRequestId = requestId,
                    details = mapOf("taskCount" to erasureTaskIds.size.toString())
                )

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
                        dsrRequestId = dsrRequestId,
                        details = mapOf("reason" to "user_not_found_or_already_deleted")
                    )
                }

                dsrService.updateStatus(
                    requestId = requestId,
                    status = "rejected",
                    handledBy = "system",
                    rejectionReason = "user_not_found_or_already_deleted",
                    notes = "Deletion target was not found"
                )

                call.respond(
                    HttpStatusCode.NotFound,
                    DeleteUserResponse(
                        success = false,
                        message = "User not found or already deleted"
                    )
                )
            }
        } catch (e: Exception) {
            dsrRequestId?.let { requestId ->
                dsrService.updateStatus(
                    requestId = requestId,
                    status = "rejected",
                    handledBy = "system",
                    rejectionReason = "exception",
                    notes = e.message ?: "unknown_error"
                )
            }
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
                    dsrRequestId = dsrRequestId,
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
