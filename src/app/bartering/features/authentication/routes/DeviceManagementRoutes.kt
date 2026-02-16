package app.bartering.features.authentication.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.model.*
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.authentication.utils.verifyRequestSignatureForDevice
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("DeviceManagementRoutes")

/**
 * Route to register a new device key.
 * This is called when a user sets up the app on a new device.
 */
fun Route.registerDeviceRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/devices/register") {
        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<RegisterDeviceRequest>(requestBody)

            // Verify the userId in the request matches the authenticated user
            if (request.userId != authenticatedUserId) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    RegisterDeviceResponse(
                        success = false,
                        message = "User ID mismatch. You can only register devices for your own account."
                    )
                )
                return@post
            }

            // Check device limits
            val activeCount = authDao.getActiveDeviceCount(request.userId)
            if (activeCount >= DeviceKeyConstraints.MAX_ACTIVE_DEVICES) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    RegisterDeviceResponse(
                        success = false,
                        message = "Maximum number of active devices (${DeviceKeyConstraints.MAX_ACTIVE_DEVICES}) reached. " +
                                "Please revoke an existing device before adding a new one.",
                        deviceLimitReached = true
                    )
                )
                return@post
            }

            // Check if device already exists
            val existingDevice = authDao.getDeviceKey(request.userId, request.deviceId)
            if (existingDevice != null) {
                // Device exists - update the key (re-registration, e.g., after app reinstall)
                authDao.deactivateDeviceKey(request.userId, request.deviceId, "replaced")
            }

            // Create device key record
            val deviceKey = DeviceKeyInfo(
                id = UUID.randomUUID().toString(),
                userId = request.userId,
                deviceId = request.deviceId,
                publicKey = request.publicKey,
                deviceName = request.deviceName,
                deviceType = request.deviceType,
                platform = request.platform,
                isActive = true,
                createdAt = Instant.now().toString()
            )

            val success = authDao.registerDeviceKey(deviceKey)

            if (success) {
                call.respond(
                    HttpStatusCode.OK,
                    RegisterDeviceResponse(
                        success = true,
                        deviceKeyId = deviceKey.id,
                        message = "Device registered successfully"
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RegisterDeviceResponse(
                        success = false,
                        message = "Failed to register device. Please try again."
                    )
                )
            }
        } catch (e: Exception) {
            log.error("Error registering device for user {}", authenticatedUserId, e)
            call.respond(
                HttpStatusCode.BadRequest,
                RegisterDeviceResponse(
                    success = false,
                    message = "Invalid request: ${e.message}"
                )
            )
        }
    }
}

/**
 * Route to list all devices for the authenticated user.
 */
fun Route.listDevicesRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/devices") {
        // --- Authentication using signature verification ---
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        try {
            val devices = authDao.getAllDeviceKeys(authenticatedUserId)
            val activeCount = devices.count { it.isActive }

            call.respond(
                HttpStatusCode.OK,
                UserDevicesResponse(
                    success = true,
                    devices = devices,
                    activeDeviceCount = activeCount,
                    totalDeviceCount = devices.size
                )
            )
        } catch (e: Exception) {
            log.error("Error listing devices for user {}", authenticatedUserId, e)
            call.respond(
                HttpStatusCode.InternalServerError,
                UserDevicesResponse(
                    success = false,
                    devices = emptyList(),
                    activeDeviceCount = 0,
                    totalDeviceCount = 0
                )
            )
        }
    }
}

/**
 * Route to revoke/deactivate a device.
 */
fun Route.revokeDeviceRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/devices/revoke") {
        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<RevokeDeviceRequest>(requestBody)

            // Verify the device belongs to the authenticated user
            val devices = authDao.getAllDeviceKeys(authenticatedUserId)
            val targetDevice = devices.find { it.deviceId == request.deviceId }

            if (targetDevice == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    RevokeDeviceResponse(
                        success = false,
                        message = "Device not found or does not belong to your account"
                    )
                )
                return@post
            }

            // Prevent revoking the last active device (user would be locked out)
            val activeDevices = devices.filter { it.isActive }
            if (targetDevice.isActive && activeDevices.size <= 1) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    RevokeDeviceResponse(
                        success = false,
                        message = "Cannot revoke your only active device. Please register a new device first."
                    )
                )
                return@post
            }

            val success = authDao.deactivateDeviceKey(
                authenticatedUserId,
                request.deviceId,
                request.reason ?: "user_revoked"
            )

            if (success) {
                call.respond(
                    HttpStatusCode.OK,
                    RevokeDeviceResponse(
                        success = true,
                        message = "Device revoked successfully"
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RevokeDeviceResponse(
                        success = false,
                        message = "Failed to revoke device"
                    )
                )
            }
        } catch (e: Exception) {
            log.error("Error revoking device for user {}", authenticatedUserId, e)
            call.respond(
                HttpStatusCode.BadRequest,
                RevokeDeviceResponse(
                    success = false,
                    message = "Invalid request: ${e.message}"
                )
            )
        }
    }
}

/**
 * Route to update device information (name, etc.).
 */
fun Route.updateDeviceRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/devices/update") {
        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<UpdateDeviceRequest>(requestBody)

            // Verify the device belongs to the authenticated user
            val devices = authDao.getAllDeviceKeys(authenticatedUserId)
            val targetDevice = devices.find { it.deviceId == request.deviceId }

            if (targetDevice == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    UpdateDeviceResponse(
                        success = false,
                        message = "Device not found or does not belong to your account"
                    )
                )
                return@post
            }

            val success = authDao.updateDeviceInfo(
                authenticatedUserId,
                request.deviceId,
                request.deviceName
            )

            if (success) {
                call.respond(
                    HttpStatusCode.OK,
                    UpdateDeviceResponse(
                        success = true,
                        message = "Device updated successfully"
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    UpdateDeviceResponse(
                        success = false,
                        message = "Failed to update device"
                    )
                )
            }
        } catch (e: Exception) {
            log.error("Error updating device for user {}", authenticatedUserId, e)
            call.respond(
                HttpStatusCode.BadRequest,
                UpdateDeviceResponse(
                    success = false,
                    message = "Invalid request: ${e.message}"
                )
            )
        }
    }
}

/**
 * Route for device migration (used by the DeviceMigrationService).
 * Transfers device registration from source to target device.
 */
fun Route.migrateDeviceRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/devices/migrate") {
        // --- Authentication using signature verification ---
        // Note: This must be called from the SOURCE device (the one being migrated from)
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<MigrateDeviceRequest>(requestBody)

            // Verify the userId matches
            // Note: In a real migration, we may need to verify the sessionId as well
            // This is a simplified implementation - the full verification should be in the migration service

            // Verify source device belongs to the authenticated user
            val sourceDevice = authDao.getDeviceKey(authenticatedUserId, request.sourceDeviceId)
            if (sourceDevice == null) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    MigrateDeviceResponse(
                        success = false,
                        sourceDeviceDeactivated = false,
                        message = "Source device not found or does not belong to your account"
                    )
                )
                return@post
            }

            // Check if target device already exists
            val existingTarget = authDao.getDeviceKey(authenticatedUserId, request.targetDeviceId)
            if (existingTarget != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    MigrateDeviceResponse(
                        success = false,
                        sourceDeviceDeactivated = false,
                        message = "Target device already exists"
                    )
                )
                return@post
            }

            // Perform the migration
            val success = authDao.migrateDeviceKey(
                userId = authenticatedUserId,
                sourceDeviceId = request.sourceDeviceId,
                targetDeviceId = request.targetDeviceId,
                targetPublicKey = request.targetPublicKey,
                targetDeviceName = request.targetDeviceName,
                targetDeviceType = request.targetDeviceType,
                targetPlatform = request.targetPlatform
            )

            if (success) {
                // Get the new device key ID
                val newDevice = authDao.getDeviceKey(authenticatedUserId, request.targetDeviceId)

                call.respond(
                    HttpStatusCode.OK,
                    MigrateDeviceResponse(
                        success = true,
                        newDeviceKeyId = newDevice?.id,
                        sourceDeviceDeactivated = true,
                        message = "Device migrated successfully"
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MigrateDeviceResponse(
                        success = false,
                        sourceDeviceDeactivated = false,
                        message = "Failed to migrate device"
                    )
                )
            }
        } catch (e: Exception) {
            log.error("Error migrating device for user {}", authenticatedUserId, e)
            call.respond(
                HttpStatusCode.BadRequest,
                MigrateDeviceResponse(
                    success = false,
                    sourceDeviceDeactivated = false,
                    message = "Invalid request: ${e.message}"
                )
            )
        }
    }
}

/**
 * Extension function to register all device management routes
 */
fun Application.deviceManagementRoutes() {
    routing {
        registerDeviceRoute()
        listDevicesRoute()
        revokeDeviceRoute()
        updateDeviceRoute()
        migrateDeviceRoute()
    }
}