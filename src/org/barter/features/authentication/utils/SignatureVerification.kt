package org.barter.features.authentication.utils

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.utils.CryptoUtils.convertRawB64KeyToECPublicKey
import java.security.Signature
import java.util.Base64
import kotlin.math.abs

/**
 * Verifies the client's request signature.
 *
 * This function checks for required authentication headers, validates the timestamp to prevent replay attacks,
 * and verifies the cryptographic signature of the request body.
 *
 * @param call The Ktor ApplicationCall, used to access headers, body, and send responses.
 * @param usersDao The DAO to retrieve the user's public key.
 * @return A Pair containing the authenticated userId and the raw request body string on success.
 *         On failure, it sends an appropriate HTTP error response and returns Pair(null, null).
 */
suspend fun verifyRequestSignature(call: ApplicationCall, usersDao: AuthenticationDaoImpl): Pair<String?, String?> {
    // --- 1. Extract Authentication Data ---
    val userId = call.request.headers["X-User-ID"]
    val timestampStr = call.request.headers["X-Timestamp"]
    val clientSignatureB64 = call.request.headers["X-Signature"]

    val requestBodyAsJsonString = call.receiveText()

    if (userId == null || timestampStr == null || clientSignatureB64 == null) {
        call.respond(HttpStatusCode.BadRequest, "Missing authentication headers")
        return Pair(null, null)
    }

    // --- 2. Retrieve the User's Public Key ---
    val userInfo = try {
        usersDao.getUserInfoById(userId)
    } catch (e: Exception) {
        e.printStackTrace()
        call.respond(HttpStatusCode.NotFound, "User not found")
        return Pair(null, null)
    }

    val userPublicKeyB64 = userInfo?.publicKey

    // --- 3. Prevent Replay Attacks by Checking the Timestamp ---
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

    // --- 4. Reconstruct the Exact Same Challenge Message ---
    val challenge = "$timestampStr.$requestBodyAsJsonString"

    try {
        // --- 5. Verify the Signature ---
        val publicKey = convertRawB64KeyToECPublicKey(userPublicKeyB64!!)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update(challenge.toByteArray())

        val signatureBytes = Base64.getDecoder().decode(clientSignatureB64)

        if (!signature.verify(signatureBytes)) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
            return Pair(null, null)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        call.respond(HttpStatusCode.InternalServerError, "Signature verification failed: ${e.message}")
        return Pair(null, null)
    }

    return Pair(userId, requestBodyAsJsonString)

}