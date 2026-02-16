package app.bartering.features.authentication.utils

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.utils.CryptoUtils.convertRawB64KeyToECPublicKey
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.security.Signature
import java.util.Base64
import kotlin.math.abs

/**
 * Verifies the client's request signature.
 *
 * This function checks for required authentication headers, validates the timestamp to prevent replay attacks,
 * and verifies the cryptographic signature of the request body against the user's device keys.
 *
 * Supports multi-device authentication where each device has its own keypair.
 *
 * @param call The Ktor ApplicationCall, used to access headers, body, and send responses.
 * @param authDao The DAO to retrieve the user's device keys.
 * @return A Pair containing the authenticated userId and the raw request body string on success.
 *         On failure, it sends an appropriate HTTP error response and returns Pair(null, null).
 */
suspend fun verifyRequestSignature(call: ApplicationCall, authDao: AuthenticationDaoImpl): Pair<String?, String?> {
    // --- 1. Extract Authentication Data ---
    val userId = call.request.headers["X-User-ID"]
    val deviceId = call.request.headers["X-Device-ID"]  // Optional: for O(1) device lookup
    val timestampStr = call.request.headers["X-Timestamp"]
    val clientSignatureB64 = call.request.headers["X-Signature"]

    val requestBodyAsJsonString = call.receiveText()

    if (userId == null || timestampStr == null || clientSignatureB64 == null) {
        call.respond(HttpStatusCode.BadRequest, "Missing authentication headers")
        return Pair(null, null)
    }

    // --- 2. Prevent Replay Attacks by Checking the Timestamp ---
    val timestamp = timestampStr.toLongOrNull()
    if (timestamp == null) {
        call.respond(HttpStatusCode.BadRequest, "Invalid timestamp format")
        return Pair(null, null)
    }
    val currentTime = System.currentTimeMillis()
    // Allow a time window of 5 minutes (300,000 ms)
    if (abs(currentTime - timestamp) > 300000) {
        call.respond(HttpStatusCode.Unauthorized, "Request has expired")
        return Pair(null, null)
    }

    // --- 3. Retrieve the User's Device Keys ---
    // Strategy:
    // - If deviceId is provided: Look up specific device key (fast, O(1))
    // - If no deviceId: Try all active keys for user (fallback, O(n))
    // - If no device keys found: Fall back to legacy user_registration_data.public_key
    
    val deviceKeys = if (deviceId != null) {
        // Fast path: Direct device lookup
        authDao.getDeviceKey(userId, deviceId)?.let { listOf(it) } ?: emptyList()
    } else {
        // Fallback: Get all active device keys for the user
        // This happens when older clients don't send deviceId, or during migration
        authDao.getAllActiveDeviceKeys(userId)
    }

    // --- 4. BACKWARD COMPATIBILITY: Try legacy public key if no device keys ---
    // This supports existing users who haven't registered device keys yet
    val userInfo = if (deviceKeys.isEmpty()) {
        try {
            authDao.getUserInfoById(userId)
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }

    // --- 5. Reconstruct the Exact Same Challenge Message ---
    val challenge = "$timestampStr.$requestBodyAsJsonString"

    // --- 6. Try to Verify Against Each Device Key ---
    val signatureBytes = try {
        Base64.getDecoder().decode(clientSignatureB64)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, "Invalid signature format")
        return Pair(null, null)
    }

    // Try each device key until one validates
    for (deviceKey in deviceKeys) {
        try {
            val publicKey = convertRawB64KeyToECPublicKey(deviceKey.publicKey)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(challenge.toByteArray())

            if (signature.verify(signatureBytes)) {
                // Success! Update last used timestamp asynchronously (don't block response)
                // Note: We don't await this to keep authentication fast
                kotlinx.coroutines.GlobalScope.launch {
                    authDao.updateDeviceLastUsed(userId, deviceKey.deviceId)
                }
                
                return Pair(userId, requestBodyAsJsonString)
            }
        } catch (e: Exception) {
            // Log error but continue trying other keys
            // This can happen if a key is corrupted or in wrong format
            continue
        }
    }

    // --- 6. BACKWARD COMPATIBILITY: Try legacy public key if no device keys worked ---
    // This supports existing users who haven't migrated to device keys yet
    if (userInfo?.publicKey != null) {
        try {
            val publicKey = convertRawB64KeyToECPublicKey(userInfo.publicKey)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(challenge.toByteArray())

            if (signature.verify(signatureBytes)) {
                // Success with legacy key!
                return Pair(userId, requestBodyAsJsonString)
            }
        } catch (e: Exception) {
            // Legacy key verification failed, continue to next fallback
        }
    }

    // --- 7. BACKWARD COMPATIBILITY: Check migration sessions for newly migrated devices ---
    // Target devices have new keypairs not yet registered in user_device_keys
    // Check if this signature matches any completed migration session's target key
    val migrationDao = org.koin.java.KoinJavaComponent.getKoin().get<app.bartering.features.migration.dao.MigrationSessionDao>()
    val completedSessions = migrationDao.getRecentCompletedSessions(userId)
    
    for (session in completedSessions) {
        // Check if signature matches the target device's ephemeral key from migration
        if (session.targetPublicKey != null) {
            try {
                val publicKey = convertRawB64KeyToECPublicKey(session.targetPublicKey)
                val sig = Signature.getInstance("SHA256withECDSA")
                sig.initVerify(publicKey)
                sig.update(challenge.toByteArray())

                if (sig.verify(signatureBytes)) {
                    // Success! This is a newly migrated device
                    // Auto-register the device key for future requests
                    if (session.targetDeviceId != null && session.targetPublicKey != null) {
                        GlobalScope.launch {
                            authDao.registerDeviceKey(
                                app.bartering.features.authentication.model.DeviceKeyInfo(
                                    id = java.util.UUID.randomUUID().toString(),
                                    userId = userId,
                                    deviceId = session.targetDeviceId,
                                    publicKey = session.targetPublicKey,
                                    deviceName = "Migrated Device",
                                    deviceType = "mobile",
                                    platform = null,
                                    isActive = true,
                                    lastUsedAt = java.time.Instant.now().toString(),
                                    createdAt = java.time.Instant.now().toString(),
                                    deactivatedAt = null,
                                    deactivatedReason = null
                                )
                            )
                        }
                    }
                    return Pair(userId, requestBodyAsJsonString)
                }
            } catch (e: Exception) {
                // This session's key doesn't match, try next
                continue
            }
        }
    }

    // --- 8. All Keys Failed ---
    call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
    return Pair(null, null)
}

/**
 * Verifies signature for a specific device (more efficient when deviceId is known).
 * This is the preferred method for new API endpoints.
 *
 * @param call The Ktor ApplicationCall
 * @param authDao The DAO to retrieve device keys
 * @param requiredDeviceId Optional device ID to enforce specific device authentication
 * @return Pair of (userId, requestBody) on success, (null, null) on failure
 */
suspend fun verifyRequestSignatureForDevice(
    call: ApplicationCall, 
    authDao: AuthenticationDaoImpl,
    requiredDeviceId: String? = null
): Pair<String?, String?> {
    val userId = call.request.headers["X-User-ID"]
    val deviceId = requiredDeviceId ?: call.request.headers["X-Device-ID"]
    val timestampStr = call.request.headers["X-Timestamp"]
    val clientSignatureB64 = call.request.headers["X-Signature"]

    val requestBodyAsJsonString = call.receiveText()

    if (userId == null || timestampStr == null || clientSignatureB64 == null) {
        call.respond(HttpStatusCode.BadRequest, "Missing authentication headers")
        return Pair(null, null)
    }

    if (deviceId == null) {
        call.respond(HttpStatusCode.BadRequest, "X-Device-ID header is required")
        return Pair(null, null)
    }

    // Timestamp validation
    val timestamp = timestampStr.toLongOrNull()
    if (timestamp == null || abs(System.currentTimeMillis() - timestamp) > 300000) {
        call.respond(HttpStatusCode.Unauthorized, "Request has expired or invalid timestamp")
        return Pair(null, null)
    }

    // Get specific device key
    val deviceKey = authDao.getDeviceKey(userId, deviceId)
        ?: run {
            call.respond(HttpStatusCode.NotFound, "Device not found or inactive")
            return Pair(null, null)
        }

    // Verify signature
    val challenge = "$timestampStr.$requestBodyAsJsonString"
    
    try {
        val publicKey = convertRawB64KeyToECPublicKey(deviceKey.publicKey)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update(challenge.toByteArray())

        val signatureBytes = Base64.getDecoder().decode(clientSignatureB64)

        if (signature.verify(signatureBytes)) {
            // Update last used asynchronously
            kotlinx.coroutines.GlobalScope.launch {
                authDao.updateDeviceLastUsed(userId, deviceId)
            }
            return Pair(userId, requestBodyAsJsonString)
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, "Signature verification failed: ${e.message}")
        return Pair(null, null)
    }

    call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
    return Pair(null, null)
}