package app.bartering.features.migration.routes

import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.model.DeviceKeyInfo
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.service.EmailService
import app.bartering.features.migration.dao.MigrationDao
import app.bartering.features.migration.model.*
import app.bartering.features.migration.templates.MigrationEmailTemplates
import app.bartering.features.profile.dao.UserProfileDaoImpl
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = LoggerFactory.getLogger("MigrationRoutes")

// ============================================================================
// EMAIL RECOVERY ROUTES
// ============================================================================

fun Route.initiateEmailRecoveryRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)
    val notificationDao: NotificationPreferencesDao by inject(NotificationPreferencesDao::class.java)
    val emailService: EmailService by inject(EmailService::class.java)

    post("/api/v1/migration/recovery/initiate") {
        try {
            val request = call.receive<InitiateRecoveryRequest>()
            val clientIP = call.request.headers["X-Forwarded-For"] ?: call.request.headers["X-Real-IP"] ?: "unknown"

            if (migrationDao.isIPRateLimited(clientIP)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, InitiateRecoveryResponse(
                    success = false, message = "Too many attempts. Try again later.", errorMessage = "Rate limited"
                ))
            }

            // Look up user by email address
            val userContacts = notificationDao.getUserByEmail(request.email)
                ?: return@post call.respond(HttpStatusCode.NotFound, InitiateRecoveryResponse(
                    success = false, message = "No account found with this email"
                ))

            val email = userContacts.email
                ?: return@post call.respond(HttpStatusCode.BadRequest, InitiateRecoveryResponse(
                    success = false, message = "No email registered"
                ))

            val result = migrationDao.createEmailRecoverySession(userId = userContacts.userId, email = email, ipAddress = clientIP)
                ?: return@post call.respond(HttpStatusCode.TooManyRequests, InitiateRecoveryResponse(
                    success = false, message = "Too many active sessions"
                ))

            val (sessionId, recoveryCode) = result

            try {
                val emailNotification = MigrationEmailTemplates.createRecoveryEmail(email, userContacts.userId, recoveryCode)
                val emailResult = emailService.sendEmail(emailNotification)
                
                if (!emailResult.success) {
                    // Mark session as failed since email couldn't be sent
                    migrationDao.failSession(sessionId, userContacts.userId, emailResult.errorMessage ?: "Email delivery failed")
                    return@post call.respond(HttpStatusCode.ServiceUnavailable, InitiateRecoveryResponse(
                        success = false, message = "Failed to send recovery email. Please try again."
                    ))
                }
            } catch (e: Exception) {
                log.error("Failed to send recovery email", e)
                migrationDao.failSession(sessionId, userContacts.userId, e.message ?: "Email service error")
                return@post call.respond(HttpStatusCode.ServiceUnavailable, InitiateRecoveryResponse(
                    success = false, message = "Failed to send recovery email. Please try again."
                ))
            }

            call.respond(HttpStatusCode.OK, InitiateRecoveryResponse(
                success = true, sessionId = sessionId, message = "Recovery code sent",
                emailMasked = maskEmail(email),
                expiresAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(MigrationConstraints.RECOVERY_EXPIRY_HOURS * 60L * 60L))
            ))
        } catch (e: Exception) {
            log.error("Error initiating recovery", e)
            call.respond(HttpStatusCode.BadRequest, InitiateRecoveryResponse(success = false, message = "Invalid request"))
        }
    }
}

fun Route.verifyRecoveryCodeRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)

    post("/api/v1/migration/recovery/verify") {
        try {
            val request = call.receive<VerifyRecoveryCodeRequest>()
            val clientIP = call.request.headers["X-Forwarded-For"] ?: call.request.headers["X-Real-IP"] ?: "unknown"

            val session = migrationDao.getSession(request.sessionId)
                ?: return@post call.respond(HttpStatusCode.NotFound, VerifyRecoveryCodeResponse(success = false, message = "Session not found"))

            val verified = migrationDao.verifyEmailRecoveryCode(
                sessionId = request.sessionId,
                plaintextCode = request.recoveryCode.uppercase().replace("-", ""),
                newDeviceId = request.newDeviceId,
                newDevicePublicKey = request.newDevicePublicKey
            )

            if (!verified) {
                migrationDao.logAudit(
                    eventType = MigrationEventType.FAILED, migrationType = MigrationType.EMAIL_RECOVERY,
                    userId = session.userId, sessionId = request.sessionId, ipAddress = clientIP,
                    details = mapOf("attempts_remaining" to (session.attemptsRemaining - 1).toString()),
                    riskScore = if (session.attemptsRemaining <= 1) 75 else 50
                )
                return@post call.respond(HttpStatusCode.Unauthorized, VerifyRecoveryCodeResponse(
                    success = false, message = "Invalid code. ${session.attemptsRemaining - 1} attempts remaining."
                ))
            }

            call.respond(HttpStatusCode.OK, VerifyRecoveryCodeResponse(
                success = true, message = "Code verified. Complete to register device."
            ))
        } catch (e: Exception) {
            log.error("Error verifying code", e)
            call.respond(HttpStatusCode.BadRequest, VerifyRecoveryCodeResponse(success = false, message = "Invalid request"))
        }
    }
}

// ============================================================================
// DEVICE MIGRATION ROUTES
// ============================================================================

fun Route.initiateDeviceMigrationRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)

    post("/api/v1/migration/device/initiate") {
        try {
            val request = call.receive<InitiateMigrationRequest>()
            val clientIP = call.request.headers["X-Forwarded-For"] ?: call.request.headers["X-Real-IP"] ?: "unknown"

            val sessionCode = migrationDao.createDeviceMigrationSession(
                userId = request.userId,
                sourceDeviceId = request.sourceDeviceId,
                sourcePublicKey = request.sourcePublicKey,
                ipAddress = clientIP
            ) ?: return@post call.respond(HttpStatusCode.TooManyRequests, InitiateMigrationResponse(
                success = false, errorMessage = "Too many active sessions"
            ))

            call.respond(HttpStatusCode.OK, InitiateMigrationResponse(
                success = true, sessionCode = sessionCode,
                expiresAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(MigrationConstraints.MIGRATION_EXPIRY_MINUTES * 60L))
            ))
        } catch (e: Exception) {
            log.error("Error initiating migration", e)
            call.respond(HttpStatusCode.BadRequest, InitiateMigrationResponse(success = false, errorMessage = "Invalid request"))
        }
    }
}

fun Route.registerMigrationTargetRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)

    post("/api/v1/migration/device/target") {
        try {
            val request = call.receive<RegisterMigrationTargetRequest>()

            val session = migrationDao.registerMigrationTarget(
                sessionCode = request.sessionCode,
                targetDeviceId = request.targetDeviceId,
                targetPublicKey = request.targetPublicKey
            ) ?: return@post call.respond(HttpStatusCode.NotFound, RegisterMigrationTargetResponse(
                success = false, errorMessage = "Invalid or expired session"
            ))

            call.respond(HttpStatusCode.OK, RegisterMigrationTargetResponse(
                success = true,
                sessionId = session.id,
                sourceDeviceId = session.sourceDeviceId,
                targetPublicKey = session.targetPublicKey,
                userId = session.userId
            ))
        } catch (e: Exception) {
            log.error("Error registering target", e)
            call.respond(HttpStatusCode.BadRequest, RegisterMigrationTargetResponse(success = false, errorMessage = "Invalid request"))
        }
    }
}

fun Route.sendMigrationPayloadRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)

    post("/api/v1/migration/device/payload") {
        try {
            val request = call.receive<SendMigrationPayloadRequest>()

            val success = migrationDao.storeMigrationPayload(
                sessionId = request.sessionId,
                encryptedPayload = Json.encodeToString(EncryptedMigrationPayloadData.serializer(), request.encryptedPayload)
            )

            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Payload stored"))
            } else {
                call.respond(HttpStatusCode.Conflict, mapOf("success" to false, "message" to "Failed to store payload"))
            }
        } catch (e: Exception) {
            log.error("Error storing payload", e)
            call.respond(HttpStatusCode.BadRequest, mapOf("success" to false, "message" to "Invalid request"))
        }
    }
}

fun Route.getMigrationPayloadRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)

    get("/api/v1/migration/payload") {
        val sessionId = call.request.queryParameters["sessionId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("success" to false, "message" to "Missing sessionId"))

        // Try to find by ID first, then by sessionCode
        val session = migrationDao.getSession(sessionId)
            ?: migrationDao.getSessionByCode(sessionId)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "message" to "Session not found"))

        // Only target device can retrieve payload
        if (session.status != "transferring" && session.status != "completed") {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "message" to "Payload not ready"))
        }

        val payload = session.encryptedPayload
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "message" to "No payload available"))

        call.respond(HttpStatusCode.OK, GetMigrationPayloadResponse(
            success = true,
            encryptedPayload = Json.decodeFromString(EncryptedMigrationPayloadData.serializer(), payload)
        ))
    }
}

// ============================================================================
// COMMON ROUTES
// ============================================================================

fun Route.completeMigrationRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    post("/api/v1/migration/complete") {
        try {
            val request = call.receive<CompleteMigrationRequest>()
            val clientIP = call.request.headers["X-Forwarded-For"] ?: call.request.headers["X-Real-IP"] ?: "unknown"

            val session = migrationDao.getSession(request.sessionId)
                ?: return@post call.respond(HttpStatusCode.NotFound, CompleteMigrationResponse(
                    success = false, message = "Session not found"
                ))

            if (session.status != "verified" && session.status != "transferring") {
                return@post call.respond(HttpStatusCode.Conflict, CompleteMigrationResponse(
                    success = false, message = "Session not ready"
                ))
            }

            if (!migrationDao.completeSession(request.sessionId)) {
                return@post call.respond(HttpStatusCode.InternalServerError, CompleteMigrationResponse(
                    success = false, message = "Failed to complete"
                ))
            }

            // Register new device
            val deviceKeyInfo = DeviceKeyInfo(
                id = UUID.randomUUID().toString(),
                userId = session.userId,
                deviceId = request.newDeviceId,
                publicKey = request.devicePublicKey,
                deviceName = request.deviceName ?: if (session.type == MigrationType.EMAIL_RECOVERY) "Recovered Device" else "Migrated Device",
                deviceType = "mobile",
                platform = "unknown",
                isActive = true,
                lastUsedAt = Instant.now().toString(),
                createdAt = Instant.now().toString()
            )

            if (!authDao.registerDeviceKey(deviceKeyInfo)) {
                return@post call.respond(HttpStatusCode.InternalServerError, CompleteMigrationResponse(
                    success = false, message = "Failed to register device"
                ))
            }

            // For email recovery: deactivate all other devices (old device is lost/broken)
            if (session.type == MigrationType.EMAIL_RECOVERY) {
                val existingDevices = authDao.getAllActiveDeviceKeys(session.userId)
                    .filter { it.deviceId != request.newDeviceId }
                
                for (oldDevice in existingDevices) {
                    authDao.deactivateDeviceKey(session.userId, oldDevice.deviceId, "device_recovered_via_email")
                    log.info("Deactivated old device {} for user {} due to email recovery", oldDevice.deviceId, session.userId)
                }
            }

            // Update user's public key in user_registration_data for device-to-device migration
            // where the keypair is imported. For email recovery, the device has a new keypair
            // so we only update device-specific key, not the master public key.
            if (session.type != MigrationType.EMAIL_RECOVERY) {
                userProfileDao.updateUserPublicKey(session.userId, request.devicePublicKey)
            }

            call.respond(HttpStatusCode.OK, CompleteMigrationResponse(
                success = true, message = "Migration completed", userId = session.userId,
                warning = if (session.type == MigrationType.EMAIL_RECOVERY) "Review security settings" else null
            ))
        } catch (e: Exception) {
            log.error("Error completing migration", e)
            call.respond(HttpStatusCode.BadRequest, CompleteMigrationResponse(success = false, message = "Invalid request"))
        }
    }
}

fun Route.getMigrationStatusRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)

    get("/api/v1/migration/status") {
        val sessionId = call.request.queryParameters["sessionId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, MigrationStatusResponse(success = false, errorMessage = "Missing sessionId"))

        // Try to find by ID first, then by sessionCode
        val session = migrationDao.getSession(sessionId)
            ?: migrationDao.getSessionByCode(sessionId)
            ?: return@get call.respond(HttpStatusCode.NotFound, MigrationStatusResponse(success = false, errorMessage = "Session not found"))

        call.respond(HttpStatusCode.OK, MigrationStatusResponse(
            success = true, sessionId = session.id, type = session.type, status = session.status,
            targetPublicKey = session.targetPublicKey,
            attemptsRemaining = session.attemptsRemaining, expiresAt = session.expiresAt
        ))
    }
}

fun Route.cancelMigrationRoute() {
    val migrationDao: MigrationDao by inject(MigrationDao::class.java)

    post("/api/v1/migration/cancel") {
        try {
            val request = call.receive<CancelMigrationRequest>()
            val cancelled = migrationDao.cancelSession(request.sessionId)

            call.respond(HttpStatusCode.OK, CancelMigrationResponse(
                success = cancelled, message = if (cancelled) "Cancelled" else "Session not found"
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, CancelMigrationResponse(success = false, message = "Invalid request"))
        }
    }
}

// ============================================================================
// ROUTE REGISTRATION
// ============================================================================

fun Application.migrationRoutes() {
    routing {
        // Email recovery
        initiateEmailRecoveryRoute()
        verifyRecoveryCodeRoute()

        // Device-to-device
        initiateDeviceMigrationRoute()
        registerMigrationTargetRoute()
        sendMigrationPayloadRoute()
        getMigrationPayloadRoute()

        // Common
        completeMigrationRoute()
        getMigrationStatusRoute()
        cancelMigrationRoute()
    }
}

// ============================================================================
// HELPERS
// ============================================================================

private fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 1) return "***" + email.substring(at)
    return email.first() + "***" + email.last() + email.substring(at)
}
