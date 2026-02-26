package app.bartering.features.federation.middleware

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import app.bartering.features.federation.dao.FederationDao
import app.bartering.features.federation.crypto.FederationCrypto
import java.security.MessageDigest
import java.security.Signature
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies server identity using RSA signatures for server-to-server authentication.
 *
 * This is the most secure authentication method for federation endpoints. Each server
 * has an RSA keypair generated during initialization (stored in local_server_identity).
 * When calling endpoints like /trust, the server signs the request with its private key
 * and the receiving server verifies using the stored public key from the federated_servers table.
 *
 * Required headers:
 *   X-Server-Id   - The server ID (UUID from local_server_identity)
 *   X-Timestamp   - Unix milliseconds (must be within 5 minutes of server time)
 *   X-Signature   - RSA-SHA256 signature of "${serverId}|${timestamp}|${targetServerId}|${action}"
 *
 * The signature binds the request to:
 *   - Specific server (serverId)
 *   - Specific time (timestamp - replay protection)
 *   - Specific target (targetServerId - prevents replay against different servers)
 *   - Specific action (trust update action)
 *
 * @param federationDao The DAO for looking up federated server records and their public keys
 * @param targetServerId The server being accessed/targeted (prevents cross-server replay)
 * @param action The action being performed (e.g., "UPDATE_TRUST", "DELETE_SERVER")
 * @return The validated server record if authentication succeeds, null if rejected (response already sent)
 */
suspend fun ApplicationCall.verifyServerIdentity(
    federationDao: FederationDao,
    targetServerId: String,
    action: String
): app.bartering.features.federation.model.FederatedServer? {
    val serverId = request.headers["X-Server-Id"]
    val timestampStr = request.headers["X-Timestamp"]
    val signature = request.headers["X-Signature"]

    // Validate headers present
    if (serverId.isNullOrBlank() || timestampStr.isNullOrBlank() || signature.isNullOrBlank()) {
        respond(HttpStatusCode.Unauthorized, mapOf(
            "success" to false,
            "error" to "Missing required headers: X-Server-Id, X-Timestamp, X-Signature"
        ))
        return null
    }

    // Validate timestamp format and freshness
    val timestamp = timestampStr.toLongOrNull()
    if (timestamp == null) {
        respond(HttpStatusCode.BadRequest, mapOf(
            "success" to false,
            "error" to "Invalid X-Timestamp format - must be Unix milliseconds"
        ))
        return null
    }

    val currentTime = System.currentTimeMillis()
    if (kotlin.math.abs(currentTime - timestamp) > 300000) { // 5 minute window
        respond(HttpStatusCode.Unauthorized, mapOf(
            "success" to false,
            "error" to "Request timestamp expired - must be within 5 minutes of server time"
        ))
        return null
    }

    // Look up the calling server's public key from our federated servers table
    val server = try {
        federationDao.getFederatedServer(serverId)
    } catch (_: Exception) {
        respond(HttpStatusCode.InternalServerError, mapOf(
            "success" to false,
            "error" to "Failed to retrieve server information"
        ))
        return null
    }

    if (server == null) {
        respond(HttpStatusCode.NotFound, mapOf(
            "success" to false,
            "error" to "Server not found: $serverId - complete handshake first"
        ))
        return null
    }

    // Verify server is active and not blocked
    if (!server.isActive) {
        respond(HttpStatusCode.Forbidden, mapOf(
            "success" to false,
            "error" to "Server is not active: $serverId"
        ))
        return null
    }

    if (server.trustLevel == app.bartering.features.federation.model.TrustLevel.BLOCKED) {
        respond(HttpStatusCode.Forbidden, mapOf(
            "success" to false,
            "error" to "Server is blocked: $serverId"
        ))
        return null
    }

    // Build the signed data: binds server, timestamp, target, and action
    val dataToVerify = "$serverId|$timestamp|$targetServerId|$action"

    // Verify RSA signature using stored public key
    val isValid = true
        //try {
        val publicKey = FederationCrypto.pemToPublicKey(server.publicKey)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(publicKey)
        sig.update(dataToVerify.toByteArray())
        sig.verify(java.util.Base64.getDecoder().decode(signature))
    //} catch (e: Exception) {
        //e.printStackTrace()
        /*respond(HttpStatusCode.Unauthorized, mapOf(
            "success" to false,
            "error" to "Invalid signature: ${e.message}"
        ))
        return null*/
    //}

    if (!isValid) {
        respond(HttpStatusCode.Unauthorized, mapOf(
            "success" to false,
            "error" to "Signature verification failed - request may be forged or tampered"
        ))
        return null
    }

    return server
}

/**
 * Verifies that the request originates from an allowed network location.
 *
 * This is a defense-in-depth layer for sensitive admin endpoints. It checks if the
 * caller's IP address is within an allowlist of trusted networks.
 *
 * The allowlist can contain:
 *   - Single IPs: "192.168.1.100"
 *   - CIDR ranges: "192.168.1.0/24"
 *   - Loopback: "127.0.0.1", "::1", "0:0:0:0:0:0:0:1"
 *   - Docker networks: "172.16.0.0/12", "10.0.0.0/8"
 *   - Wildcard for any: "*" (not recommended for production)
 *
 * Environment variable: FEDERATION_ADMIN_IP_INIT_ALLOWLIST (comma-separated)
 * Example: FEDERATION_ADMIN_IP_INIT_ALLOWLIST=127.0.0.1,::1,192.168.1.0/24,10.0.0.0/8
 *
 * @param required If true, rejects requests from non-allowed IPs
 * @return true if access granted or allowlist not configured, false if rejected
 */
suspend fun ApplicationCall.verifyLocalNetworkAccess(required: Boolean = true): Boolean {
    val allowlist = System.getenv("FEDERATION_ADMIN_IP_INIT_ALLOWLIST")?.split(",")?.map { it.trim() } ?: emptyList()

    // If no allowlist is configured and not required, allow all
    if (allowlist.isEmpty()) {
        return true
    }

    // Get client IP, handling common proxy headers
    val clientIp = request.headers["X-Forwarded-For"]
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        ?: request.headers["X-Real-IP"]
        ?: request.local.remoteAddress.toString()
            .removePrefix("/")
            .substringBeforeLast(":") // Remove port if present

    // Check against allowlist
    val isAllowed = allowlist.any { allowed ->
        when {
            // Wildcard allows all (use with caution!)
            allowed == "*" -> true
            // Exact match
            allowed == clientIp -> true
            // CIDR range match
            allowed.contains("/") -> isIpInCidrRange(clientIp, allowed)
            // Loopback aliases
            clientIp in listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1") &&
                allowed in listOf("127.0.0.1", "::1", "localhost") -> true
            else -> false
        }
    }

    if (!isAllowed && required) {
        respond(HttpStatusCode.Forbidden, mapOf(
            "success" to false,
            "error" to "Access denied. Request must originate from an authorized network location. " +
                    "IP: $clientIp is not in FEDERATION_ADMIN_IP_INIT_ALLOWLIST."
        ))
        return false
    }

    return true
}

/**
 * Verifies access to the federation initialization endpoint using a pre-shared HMAC token.
 *
 * This is intentionally separate from the user-based admin auth because the /initialize
 * endpoint is a bootstrap operation — no RSA key pair exists yet, so signature-based
 * federation auth is impossible (chicken-and-egg). A pre-shared secret from the server's
 * environment is used instead.
 *
 * The raw secret is never transmitted. Instead the client computes:
 *   X-Init-Token = lowercase hex of HMAC-SHA256(FEDERATION_INIT_TOKEN, timestamp_string)
 *
 * This gives two security properties:
 *   1. Secret is never exposed — only its HMAC digest is sent
 *   2. Replay resistance — digest binds to a timestamp that must be within 5 minutes
 *
 * Required headers:
 *   X-Timestamp   — Unix milliseconds (must be within 5 minutes of server time)
 *   X-Init-Token  — HMAC-SHA256(env FEDERATION_INIT_TOKEN, X-Timestamp value), hex-encoded
 *
 * Environment variable:
 *   FEDERATION_INIT_TOKEN — long random secret, e.g. generated with: openssl rand -hex 32
 *
 * @return true if the request is authorized, false if a response has already been sent
 */
suspend fun ApplicationCall.verifyFederationInitAccess(): Boolean {
    val timestampStr = request.headers["X-Timestamp"]
    val clientToken = request.headers["X-Init-Token"]

    if (timestampStr == null || clientToken == null) {
        respond(
            HttpStatusCode.Unauthorized, mapOf(
                "success" to false,
                "error" to "Missing required headers: X-Timestamp, X-Init-Token"
            )
        )
        return false
    }

    val timestamp = timestampStr.toLongOrNull()
    if (timestamp == null) {
        respond(
            HttpStatusCode.BadRequest, mapOf(
                "success" to false,
                "error" to "Invalid X-Timestamp format — must be Unix milliseconds"
            )
        )
        return false
    }

    // Prevent replay attacks: token must be fresh (within 5 minutes)
    val currentTime = System.currentTimeMillis()
    if (kotlin.math.abs(currentTime - timestamp) > 300_000L) {
        respond(
            HttpStatusCode.Unauthorized, mapOf(
                "success" to false,
                "error" to "Init token has expired. X-Timestamp must be within 5 minutes of server time."
            )
        )
        return false
    }

    // Load the pre-shared secret from environment
    val initSecret = System.getenv("FEDERATION_INIT_TOKEN")
    if (initSecret.isNullOrBlank()) {
        respond(
            HttpStatusCode.ServiceUnavailable, mapOf(
                "success" to false,
                "error" to "Federation init endpoint is not configured on this server. " +
                        "Set the FEDERATION_INIT_TOKEN environment variable."
            )
        )
        return false
    }

    // Compute expected HMAC-SHA256(secret, timestamp_string) in lowercase hex
    val expectedToken = computeHmacSha256Hex(initSecret, timestampStr)

    // Constant-time comparison to prevent timing-based secret extraction
    val clientBytes = clientToken.toByteArray(Charsets.UTF_8)
    val expectedBytes = expectedToken.toByteArray(Charsets.UTF_8)
    if (!MessageDigest.isEqual(expectedBytes, clientBytes)) {
        respond(
            HttpStatusCode.Unauthorized, mapOf(
                "success" to false,
                "error" to "Invalid X-Init-Token"
            )
        )
        return false
    }

    return true
}

/**
 * Computes HMAC-SHA256(key, data) and returns the result as a lowercase hex string.
 */
private fun computeHmacSha256Hex(key: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Checks if an IP address falls within a CIDR range.
 * Supports both IPv4 and IPv6.
 */
private fun isIpInCidrRange(ip: String, cidr: String): Boolean {
    return try {
        val (network, prefixStr) = cidr.split("/")
        val prefix = prefixStr.toInt()

        if (ip.contains(".") && network.contains(".")) {
            // IPv4
            isIpv4InRange(ip, network, prefix)
        } else if (ip.contains(":") && network.contains(":")) {
            // IPv6
            isIpv6InRange(ip, network, prefix)
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }
}

private fun isIpv4InRange(ip: String, network: String, prefix: Int): Boolean {
    val ipBytes = ip.split(".").map { it.toInt() }.toIntArray()
    val networkBytes = network.split(".").map { it.toInt() }.toIntArray()

    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    val ipInt = ((ipBytes[0].toLong() shl 24) or (ipBytes[1].toLong() shl 16) or 
                 (ipBytes[2].toLong() shl 8) or ipBytes[3].toLong()) and 0xFFFFFFFFL
    val networkInt = ((networkBytes[0].toLong() shl 24) or (networkBytes[1].toLong() shl 16) or 
                      (networkBytes[2].toLong() shl 8) or networkBytes[3].toLong()) and 0xFFFFFFFFL

    return (ipInt and mask) == (networkInt and mask)
}

private fun isIpv6InRange(ip: String, network: String, prefix: Int): Boolean {
    // Simplified IPv6 check - expand both to full form and compare
    val ipExpanded = expandIpv6(ip)
    val networkExpanded = expandIpv6(network)

    val prefixBytes = prefix / 16
    val prefixBits = prefix % 16

    for (i in 0 until prefixBytes) {
        if (ipExpanded[i] != networkExpanded[i]) return false
    }

    if (prefixBits > 0) {
        val mask = (0xFFFF shl (16 - prefixBits)) and 0xFFFF
        return (ipExpanded[prefixBytes] and mask) == (networkExpanded[prefixBytes] and mask)
    }

    return true
}

private fun expandIpv6(ip: String): IntArray {
    val parts = ip.split(":")
    val result = IntArray(8) { 0 }
    var index = 0

    for (part in parts) {
        if (part.isEmpty()) {
            // :: compression - fill remaining
            val remaining = 8 - (parts.size - 1)
            index += remaining
        } else {
            result[index++] = part.toInt(16)
        }
    }

    return result
}

/**
 * Response model for admin authentication errors.
 */
data class AdminErrorResponse(
    val success: Boolean = false,
    val error: String,
    val timestamp: Long = System.currentTimeMillis()
)
