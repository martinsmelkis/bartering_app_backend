package app.bartering.features.authentication.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.model.AccountDeletionByEmailResponse
import app.bartering.features.authentication.model.ConfirmAccountDeletionByEmailRequest
import app.bartering.features.authentication.model.DeleteUserRequest
import app.bartering.features.authentication.model.DeleteUserResponse
import app.bartering.features.authentication.model.RequestAccountDeletionByEmailRequest
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.compliance.service.ComplianceAuditService
import app.bartering.features.compliance.service.DataSubjectRequestService
import app.bartering.features.compliance.service.ErasureTaskService
import app.bartering.features.compliance.service.LegalHoldService
import app.bartering.features.migration.dao.MigrationDao
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.EmailNotification
import app.bartering.features.notifications.service.EmailService
import app.bartering.utils.HashUtils
import app.bartering.utils.SecurityUtils
import org.koin.java.KoinJavaComponent.inject
import java.util.Base64

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
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)
    val notificationDao: NotificationPreferencesDao by inject(NotificationPreferencesDao::class.java)
    val emailService: EmailService by inject(EmailService::class.java)

    suspend fun performDeletionFlow(
        userIdToDelete: String,
        requestedBy: String?,
        actorType: String,
        requestSource: String,
        requestIdHeader: String?,
        xForwardedFor: String?,
        xRealIp: String?,
        xDeviceId: String?
    ): Pair<HttpStatusCode, DeleteUserResponse> {
        val normalizedActorType = actorType.ifBlank { "system" }
        val ipHash = (xForwardedFor?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
            ?: xRealIp?.takeIf { it.isNotBlank() })?.let { HashUtils.sha256(it) }
        val deviceIdHash = xDeviceId?.takeIf { it.isNotBlank() }?.let { HashUtils.sha256(it) }

        var dsrRequestId: Long? = null

        return try {
            val requestId = dsrService.createRequest(
                userId = userIdToDelete,
                requestType = "deletion",
                requestedBy = requestedBy,
                requestSource = requestSource,
                reason = "gdpr_right_to_erasure"
            )
            dsrRequestId = requestId

            complianceAuditService.logEvent(
                actorType = normalizedActorType,
                actorId = requestedBy,
                eventType = "ACCOUNT_DELETION_REQUESTED",
                entityType = "user_account",
                entityId = userIdToDelete,
                purpose = "gdpr_right_to_erasure",
                outcome = "success",
                requestId = requestIdHeader,
                ipHash = ipHash,
                deviceIdHash = deviceIdHash,
                dsrRequestId = requestId
            )

            val hasDeletionHold = legalHoldService.hasActiveHold(userIdToDelete, "deletion")
            if (hasDeletionHold) {
                dsrService.updateStatus(
                    requestId = requestId,
                    status = "rejected",
                    handledBy = "system",
                    rejectionReason = "blocked_by_legal_hold",
                    notes = "Account deletion blocked due to active legal hold"
                )

                complianceAuditService.logEvent(
                    actorType = normalizedActorType,
                    actorId = requestedBy,
                    eventType = "ACCOUNT_DELETION_BLOCKED_BY_LEGAL_HOLD",
                    entityType = "user_account",
                    entityId = userIdToDelete,
                    purpose = "legal_hold_enforcement",
                    outcome = "denied",
                    requestId = requestIdHeader,
                    ipHash = ipHash,
                    deviceIdHash = deviceIdHash,
                    dsrRequestId = requestId
                )

                return HttpStatusCode.Conflict to DeleteUserResponse(
                    success = false,
                    message = "Account deletion is temporarily blocked due to an active legal hold"
                )
            }

            dsrService.updateStatus(
                requestId = requestId,
                status = "in_progress",
                handledBy = "system",
                notes = "Account deletion processing started"
            )

            val deleted = authDao.deleteUserAndAllData(userIdToDelete)

            if (deleted) {
                complianceAuditService.logEvent(
                    actorType = normalizedActorType,
                    actorId = requestedBy,
                    eventType = "ACCOUNT_DELETION_COMPLETED",
                    entityType = "user_account",
                    entityId = userIdToDelete,
                    purpose = "gdpr_right_to_erasure",
                    outcome = "success",
                    requestId = requestIdHeader,
                    ipHash = ipHash,
                    deviceIdHash = deviceIdHash,
                    dsrRequestId = requestId
                )

                dsrService.completeRequest(
                    requestId = requestId,
                    handledBy = "system",
                    notes = "Account deletion completed successfully"
                )

                val erasureTaskIds = erasureTaskService.createDefaultErasureTasks(
                    userId = userIdToDelete,
                    requestedBy = requestedBy ?: "system"
                )

                complianceAuditService.logEvent(
                    actorType = "system",
                    actorId = requestedBy,
                    eventType = "ERASURE_TASKS_REGISTERED",
                    entityType = "compliance_erasure_task",
                    entityId = userIdToDelete,
                    purpose = "gdpr_right_to_erasure",
                    outcome = "success",
                    requestId = requestIdHeader,
                    dsrRequestId = requestId,
                    details = mapOf("taskCount" to erasureTaskIds.size.toString())
                )

                HttpStatusCode.OK to DeleteUserResponse(
                    success = true,
                    message = "User account and all associated data have been permanently deleted"
                )
            } else {
                complianceAuditService.logEvent(
                    actorType = normalizedActorType,
                    actorId = requestedBy,
                    eventType = "ACCOUNT_DELETION_COMPLETED",
                    entityType = "user_account",
                    entityId = userIdToDelete,
                    purpose = "gdpr_right_to_erasure",
                    outcome = "error",
                    requestId = requestIdHeader,
                    ipHash = ipHash,
                    deviceIdHash = deviceIdHash,
                    dsrRequestId = dsrRequestId,
                    details = mapOf("reason" to "user_not_found_or_already_deleted")
                )

                dsrService.updateStatus(
                    requestId = requestId,
                    status = "rejected",
                    handledBy = "system",
                    rejectionReason = "user_not_found_or_already_deleted",
                    notes = "Deletion target was not found"
                )

                HttpStatusCode.NotFound to DeleteUserResponse(
                    success = false,
                    message = "User not found or already deleted"
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

            complianceAuditService.logEvent(
                actorType = normalizedActorType,
                actorId = requestedBy,
                eventType = "ACCOUNT_DELETION_COMPLETED",
                entityType = "user_account",
                entityId = userIdToDelete,
                purpose = "gdpr_right_to_erasure",
                outcome = "error",
                requestId = requestIdHeader,
                ipHash = ipHash,
                deviceIdHash = deviceIdHash,
                dsrRequestId = dsrRequestId,
                details = mapOf("error" to (e.message ?: "unknown_error"))
            )

            HttpStatusCode.InternalServerError to DeleteUserResponse(
                success = false,
                message = "An error occurred while deleting the user: ${e.message}"
            )
        }
    }

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

        val (statusCode, response) = performDeletionFlow(
            userIdToDelete = userIdToDelete,
            requestedBy = authenticatedUserId,
            actorType = "user",
            requestSource = "user",
            requestIdHeader = call.request.headers["X-Request-ID"],
            xForwardedFor = call.request.headers["X-Forwarded-For"],
            xRealIp = call.request.headers["X-Real-IP"],
            xDeviceId = call.request.headers["X-Device-ID"]
        )

        call.respond(statusCode, response)
    }

    post("/api/v1/authentication/account-deletion/request") {
        val request = try {
            call.receive<RequestAccountDeletionByEmailRequest>()
        } catch (_: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                AccountDeletionByEmailResponse(false, "Invalid request format")
            )
            return@post
        }

        val normalizedEmail = request.email.trim().lowercase()
        if (!SecurityUtils.isValidEmail(normalizedEmail)) {
            call.respond(
                HttpStatusCode.BadRequest,
                AccountDeletionByEmailResponse(false, "Invalid email format")
            )
            return@post
        }

        val userContacts = notificationDao.getUserByEmail(normalizedEmail)
        if (userContacts == null || userContacts.userId.isBlank() || userContacts.email.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.OK,
                AccountDeletionByEmailResponse(
                    true,
                    "If an account with this email exists, a deletion confirmation link has been sent."
                )
            )
            return@post
        }

        val userId = userContacts.userId
        val sessionResult = migrationDao.createEmailRecoverySession(
            userId = userId,
            email = normalizedEmail,
            ipAddress = call.request.headers["X-Forwarded-For"]
                ?: call.request.headers["X-Real-IP"]
                ?: call.request.local.remoteAddress
        )

        if (sessionResult == null) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                AccountDeletionByEmailResponse(false, "Too many requests. Please try again later.")
            )
            return@post
        }

        val (sessionId, rawCode) = sessionResult
        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$sessionId:$rawCode".toByteArray(Charsets.UTF_8))

        val publicUrl = System.getenv("PUBLIC_URL")?.trimEnd('/') ?: "https://bartering.app"
        val confirmUrl = "$publicUrl/delete-account?token=$token"

        val emailMessage = EmailNotification(
            to = listOf(normalizedEmail),
            from = System.getenv("MAILJET_FROM_EMAIL") ?: "info@bartering.app",
            fromName = "Bartering Privacy",
            subject = "Confirm your account deletion request",
            textBody = """
                You requested account deletion.
                Confirm link:
                $confirmUrl

                If confirmed, we will permanently delete:
                - Account registration data and profile
                - Device keys and migration/recovery sessions
                - Postings and associated uploaded images
                - Attributes, relationships, reports, and favorites/match history
                - Messages, read receipts, encrypted file metadata, and chat response stats
                - Reviews, reputation, transactions, moderation/appeals, and review audit data
                - Notification contacts and notification preferences
                - Presence/activity cache entries and related analytics/location tracking rows

                If this wasn't you, ignore this email.
            """.trimIndent(),
            htmlBody = """
                <p>Hello,</p>
                <p>We received an account deletion request for this email.</p>
                <p><a href="$confirmUrl">Confirm account deletion</a></p>
                <p>If confirmed, we will permanently delete the following data categories:</p>
                <ul>
                    <li>Account registration data and profile</li>
                    <li>Device keys and migration/recovery sessions</li>
                    <li>Postings and associated uploaded images</li>
                    <li>Attributes, relationships, reports, and favorites/match history</li>
                    <li>Messages, read receipts, encrypted file metadata, and chat response stats</li>
                    <li>Reviews, reputation, transactions, moderation/appeals, and review audit data</li>
                    <li>Notification contacts and notification preferences</li>
                    <li>Presence/activity cache entries and related analytics/location tracking rows</li>
                </ul>
                <p>If this wasn't you, you can safely ignore this email.</p>
            """.trimIndent(),
            tags = listOf("gdpr", "account_deletion"),
            metadata = mapOf("type" to "account_deletion", "sessionId" to sessionId)
        )

        val emailResult = emailService.sendEmail(emailMessage)
        if (!emailResult.success) {
            migrationDao.failSession(sessionId, userId, emailResult.errorMessage ?: "email_send_failed")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                AccountDeletionByEmailResponse(false, "Failed to send confirmation email. Please try again.")
            )
            return@post
        }

        call.respond(
            HttpStatusCode.OK,
            AccountDeletionByEmailResponse(
                true,
                "If an account with this email exists, a deletion confirmation link has been sent."
            )
        )
    }

    post("/api/v1/authentication/account-deletion/confirm") {
        val request = try {
            call.receive<ConfirmAccountDeletionByEmailRequest>()
        } catch (_: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                AccountDeletionByEmailResponse(false, "Invalid request format")
            )
            return@post
        }

        val decodedToken = try {
            String(Base64.getUrlDecoder().decode(request.token), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

        if (decodedToken.isNullOrBlank() || !decodedToken.contains(":")) {
            call.respond(
                HttpStatusCode.BadRequest,
                AccountDeletionByEmailResponse(false, "Invalid token")
            )
            return@post
        }

        val sessionId = decodedToken.substringBefore(":").trim()
        val code = decodedToken.substringAfter(":").trim().replace("-", "")
        if (sessionId.isBlank() || code.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                AccountDeletionByEmailResponse(false, "Invalid token")
            )
            return@post
        }

        val session = migrationDao.getSession(sessionId)
        if (session == null || session.type != "email_recovery") {
            call.respond(
                HttpStatusCode.NotFound,
                AccountDeletionByEmailResponse(false, "Deletion session not found")
            )
            return@post
        }

        val verified = migrationDao.verifyEmailRecoveryCode(
            sessionId = sessionId,
            plaintextCode = code,
            newDeviceId = "account-deletion",
            newDevicePublicKey = "account-deletion"
        )

        if (!verified) {
            call.respond(
                HttpStatusCode.Unauthorized,
                AccountDeletionByEmailResponse(false, "Invalid or expired token")
            )
            return@post
        }

        val (statusCode, response) = performDeletionFlow(
            userIdToDelete = session.userId,
            requestedBy = session.userId,
            actorType = "user",
            requestSource = "email_link",
            requestIdHeader = call.request.headers["X-Request-ID"],
            xForwardedFor = call.request.headers["X-Forwarded-For"],
            xRealIp = call.request.headers["X-Real-IP"],
            xDeviceId = call.request.headers["X-Device-ID"]
        )

        call.respond(
            statusCode,
            AccountDeletionByEmailResponse(response.success, response.message)
        )
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
