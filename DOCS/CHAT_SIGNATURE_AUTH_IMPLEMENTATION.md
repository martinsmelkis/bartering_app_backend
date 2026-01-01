# Chat WebSocket Signature-Based Authentication

## Overview

The chat WebSocket now uses **cryptographic signature verification** for authentication, matching
the same security model used in REST API endpoints. This ensures that only users who possess the
private key corresponding to their registered public key can connect to the chat.

## Security Benefits

✅ **No session tokens needed** - Authentication uses public key cryptography  
✅ **Cannot be spoofed** - Requires possession of the private key  
✅ **Replay attack protection** - 5-minute timestamp window  
✅ **Matches REST API security** - Consistent authentication across the app  
✅ **Perfect for E2EE** - Same keys used for message encryption

## How It Works

### 1. Client-Side (Authentication Request)

The client must send an `AuthRequest` as the first message after connecting to the WebSocket:

```kotlin
data class AuthRequest(
    val userId: String,           // The user's ID
    val peerUserId: String,       // The ID of the user they want to chat with
    val publicKey: String,        // Their public key (Base64-encoded)
    val timestamp: Long,          // Current Unix timestamp in milliseconds
    val signature: String         // ECDSA signature (Base64-encoded)
)
```

### 2. Challenge Format

The signature must be computed over the following challenge string:

```
challenge = "timestamp.userId.peerUserId"
```

**Example:**

```
1733146800000.24691476-3ce0-4800-bd01-55d40a900ea9.57cab1d9-15c1-40a7-8df9-0c3c8095b2b7
```

### 3. Signature Generation (Client-Side)

```kotlin
// 1. Create the challenge
val timestamp = System.currentTimeMillis()
val challenge = "$timestamp.$userId.$peerUserId"

// 2. Sign with ECDSA using the private key
val signature = Signature.getInstance("SHA256withECDSA")
signature.initSign(privateKey)
signature.update(challenge.toByteArray())
val signatureBytes = signature.sign()

// 3. Encode to Base64
val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

// 4. Send AuthRequest
val authRequest = AuthRequest(
    userId = userId,
    peerUserId = peerUserId,
    publicKey = publicKeyBase64,
    timestamp = timestamp,
    signature = signatureBase64
)
```

### 4. Server-Side Verification

The server performs the following checks:

#### Step 1: Validate UserId

```kotlin
if (authRequest.userId.isBlank()) {
    // Reject with 400 - Invalid userId
}
```

#### Step 2: Prevent Replay Attacks

```kotlin
val currentTime = System.currentTimeMillis()
if (abs(currentTime - authRequest.timestamp) > 300000) { // 5 minute window
    // Reject with 401 - Expired timestamp
}
```

#### Step 3: Verify Public Key Ownership

```kotlin
// Fetch the registered public key from database
val registeredPublicKey = usersDao.getUserPublicKeyById(authRequest.userId)

// Ensure provided key matches registered key
if (authRequest.publicKey != registeredPublicKey) {
    // Reject with 403 - Invalid public key
}
```

#### Step 4: Verify Signature

```kotlin
// Reconstruct the same challenge
val challenge = "${authRequest.timestamp}.${authRequest.userId}.${authRequest.peerUserId}"

// Verify using ECDSA
val publicKey = CryptoUtils.convertRawB64KeyToECPublicKey(registeredPublicKey)
val signature = Signature.getInstance("SHA256withECDSA")
signature.initVerify(publicKey)
signature.update(challenge.toByteArray())

val signatureBytes = Base64.getDecoder().decode(authRequest.signature)

if (!signature.verify(signatureBytes)) {
    // Reject with 403 - Invalid signature
}
```

#### Step 5: Authentication Success

```kotlin
// Add user to connection manager
connectionManager.addConnection(authRequest.userId, currentConnection)

// Send AuthResponse
AuthResponse(success = true, message = "Authenticated")
```

## Example Flow

### Successful Authentication

```
Client                              Server
  |                                   |
  |------ Connect to /chat ---------> |
  |                                   |
  |------ AuthRequest --------------->|
  |  {                                |
  |    userId: "abc123",              |
  |    peerUserId: "def456",          |
  |    publicKey: "BH8x...",          |
  |    timestamp: 1733146800000,      |
  |    signature: "MEU..."            |
  |  }                                |
  |                                   |--- Validate timestamp
  |                                   |--- Fetch public key from DB
  |                                   |--- Verify public key matches
  |                                   |--- Verify signature
  |                                   |
  |<----- AuthResponse -------------- |
  |  {                                |
  |    success: true,                 |
  |    message: "Authenticated"       |
  |  }                                |
  |                                   |
  |<----- Offline Messages ---------- |
  |                                   |
  |------ Chat Messages ------------->|
  |<----- Chat Messages --------------|
```

### Failed Authentication (Invalid Signature)

```
Client                              Server
  |                                   |
  |------ Connect to /chat ---------> |
  |                                   |
  |------ AuthRequest --------------->|
  |  { signature: "INVALID..." }      |
  |                                   |--- Signature verification fails
  |                                   |
  |<----- AuthResponse -------------- |
  |  {                                |
  |    success: false,                |
  |    message: "Invalid signature"   |
  |  }                                |
  |                                   |
  |<----- Connection Closed --------- |
```

## Error Codes

| Error | Reason | HTTP-Equivalent |
|-------|--------|-----------------|
| `Invalid userId` | Empty or missing userId | 400 Bad Request |
| `Expired timestamp` | Timestamp outside 5-min window | 401 Unauthorized |
| `User not found` | UserId not in database | 404 Not Found |
| `Invalid public key` | Provided key ≠ registered key | 403 Forbidden |
| `Invalid signature` | Signature verification failed | 403 Forbidden |
| `Signature verification failed` | Exception during verification | 500 Internal Error |

## Security Considerations

### Replay Attack Protection

The 5-minute timestamp window prevents replay attacks. Even if an attacker intercepts an
`AuthRequest`, they can only use it within 5 minutes, and only if:

- They haven't already connected (server closes old sessions)
- The timestamp is still valid

### Man-in-the-Middle Protection

- WebSocket should use **WSS (WebSocket Secure)** in production
- TLS/SSL prevents interception of authentication messages
- Even if intercepted, signatures cannot be forged without the private key

### Brute Force Protection

Consider adding:

- Rate limiting on failed authentication attempts
- IP-based blocking after N failures
- CAPTCHA for suspicious patterns

## Client Implementation Example

### Android/Kotlin Client

```kotlin
class ChatClient(
    private val userId: String,
    private val peerUserId: String,
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey
) {
    private lateinit var webSocket: WebSocket

    fun connect() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("wss://your-server.com/chat")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Send authentication request
                val authRequest = createAuthRequest()
                webSocket.send(Json.encodeToString(authRequest))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.decodeFromString<SocketMessage>(text)
                when (message) {
                    is AuthResponse -> handleAuthResponse(message)
                    is ServerChatMessage -> handleChatMessage(message)
                    is ErrorMessage -> handleError(message)
                }
            }
        })
    }

    private fun createAuthRequest(): AuthRequest {
        val timestamp = System.currentTimeMillis()
        val challenge = "$timestamp.$userId.$peerUserId"

        // Sign the challenge
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(challenge.toByteArray())
        val signatureBytes = signature.sign()
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

        // Encode public key
        val publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded)

        return AuthRequest(
            userId = userId,
            peerUserId = peerUserId,
            publicKey = publicKeyBase64,
            timestamp = timestamp,
            signature = signatureBase64
        )
    }
}
```

## Testing

### Testing Valid Authentication

```bash
# Generate test signature using your private key
timestamp=$(date +%s%3N)
challenge="$timestamp.$userId.$peerUserId"
signature=$(echo -n "$challenge" | openssl dgst -sha256 -sign private_key.pem | base64)

# Connect and authenticate
wscat -c ws://localhost:8081/chat
> {
    "userId": "abc123",
    "peerUserId": "def456",
    "publicKey": "BH8x...",
    "timestamp": 1733146800000,
    "signature": "MEU..."
}
```

### Testing Expired Timestamp

```bash
# Use old timestamp (> 5 minutes ago)
old_timestamp=$(($(date +%s%3N) - 400000))
# ... should receive "Authentication request has expired"
```

### Testing Invalid Signature

```bash
# Use wrong signature
# ... should receive "Invalid signature"
```
