# Federation Implementation Guide

## Quick Start

This guide provides step-by-step instructions for implementing the federation feature.

## Current Status

✅ **Phase 1: Foundation (COMPLETE)**
- Database schema designed and migration file created
- Data models and DTOs defined
- Cryptographic utilities implemented
- Service interface defined
- Route placeholders created
- Koin DI module configured

🚧 **Next Steps: Phase 2 - Core Federation**

## File Structure

```
src/org/barter/features/federation/
├── crypto/
│   └── FederationCrypto.kt         # Key generation, signing, verification
├── dao/                             # (TODO: Create DAO implementations)
├── db/
│   ├── FederatedServersTable.kt    # Trusted servers
│   ├── FederatedUsersTable.kt      # Cached remote users
│   ├── FederatedPostingsTable.kt   # Cached remote postings
│   ├── FederationAuditLogTable.kt  # Audit trail
│   └── LocalServerIdentityTable.kt # This server's identity
├── di/
│   └── FederationModule.kt         # Koin DI configuration
├── model/
│   ├── FederatedServer.kt          # Server models and enums
│   ├── FederatedUser.kt            # User federation models
│   ├── FederatedPosting.kt         # Posting federation models
│   ├── FederationProtocol.kt       # Request/Response DTOs
│   ├── FederationAuditLog.kt       # Audit log models
│   ├── LocalServerIdentity.kt      # Server identity models
│   └── Serializers.kt              # Custom JSON serializers
├── routes/
│   └── FederationRoutes.kt         # API endpoints
├── service/
│   └── FederationService.kt        # Business logic
├── IMPLEMENTATION_GUIDE.md         # This file
└── README.md                       # Feature documentation

resources/db/migration/
└── V2__Federation.sql              # Database migration
```

## Implementation Phases

### Phase 2: Core Federation (Next Priority)

#### Step 1: Create DAO Layer

Create `dao/FederationDao.kt` and `dao/FederationDaoImpl.kt`:

```kotlin
interface FederationDao {
    suspend fun getLocalServerIdentity(): LocalServerIdentity?
    suspend fun saveLocalServerIdentity(identity: LocalServerIdentity): Boolean
    suspend fun getFederatedServer(serverId: String): FederatedServer?
    suspend fun saveFederatedServer(server: FederatedServer): Boolean
    suspend fun updateFederatedServer(server: FederatedServer): Boolean
    suspend fun listFederatedServers(trustLevel: TrustLevel? = null): List<FederatedServer>
    suspend fun deleteFederatedServer(serverId: String): Boolean
    suspend fun logAuditEvent(log: FederationAuditLog): Boolean
}
```

**Key Operations:**
- CRUD for local server identity
- CRUD for federated servers
- Query servers by trust level
- Audit log persistence

#### Step 2: Implement FederationService Logic

Update `service/FederationServiceImpl.kt` to:

1. **Initialize Local Server**
   ```kotlin
   override suspend fun initializeLocalServer(...): LocalServerIdentity {
       // Check if identity exists
       val existing = federationDao.getLocalServerIdentity()
       if (existing != null) return existing
       
       // Generate new keys
       val keyPair = FederationCrypto.generateKeyPair()
       val serverId = FederationCrypto.generateServerId()
       
       // Create identity
       val identity = LocalServerIdentity(
           serverId = serverId,
           publicKey = FederationCrypto.publicKeyToPem(keyPair.public),
           privateKey = FederationCrypto.privateKeyToPem(keyPair.private),
           // ... other fields
       )
       
       // Save to database
       federationDao.saveLocalServerIdentity(identity)
       return identity
   }
   ```

2. **Handshake Implementation**
   - Create signed request
   - Send HTTP POST to target server
   - Verify response signature
   - Save federated server
   - Log audit event

3. **Signature Verification**
   ```kotlin
   override suspend fun verifyServerSignature(
       serverId: String, 
       data: String, 
       signature: String
   ): Boolean {
       val server = federationDao.getFederatedServer(serverId) ?: return false
       val publicKey = FederationCrypto.pemToPublicKey(server.publicKey)
       return FederationCrypto.verify(data, signature, publicKey)
   }
   ```

#### Step 3: Add Signature Verification Middleware

Create `routes/FederationAuthMiddleware.kt`:

```kotlin
fun Route.withFederationAuth(callback: Route.() -> Unit) {
    intercept(ApplicationCallPipeline.Call) {
        val serverId = call.request.header("X-Server-ID")
        val timestamp = call.request.header("X-Timestamp")?.toLongOrNull()
        val signature = call.request.header("X-Signature")
        
        if (serverId == null || timestamp == null || signature == null) {
            call.respond(HttpStatusCode.Unauthorized, "Missing auth headers")
            return@intercept finish()
        }
        
        // Verify timestamp freshness (prevent replay attacks)
        val now = System.currentTimeMillis()
        if (abs(now - timestamp) > 300_000) { // 5 minutes
            call.respond(HttpStatusCode.Unauthorized, "Timestamp too old")
            return@intercept finish()
        }
        
        // Get request body for signature verification
        val body = call.receiveText()
        
        // Verify signature
        val isValid = federationService.verifyServerSignature(
            serverId, "$serverId|$timestamp|$body", signature
        )
        
        if (!isValid) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
            return@intercept finish()
        }
        
        // Store serverId in call attributes for later use
        call.attributes.put(ServerIdKey, serverId)
    }
    
    callback()
}
```

#### Step 4: Implement Federation Routes

Update `routes/FederationRoutes.kt`:

1. **Server Info Endpoint** (already has basic structure, complete it)
2. **Handshake Endpoint** - Accept handshakes from other servers
3. **Add authentication middleware** to protected endpoints

Example handshake implementation:
```kotlin
post("/handshake") {
    try {
        val request = call.receive<FederationHandshakeRequest>()
        
        // Verify timestamp
        val now = System.currentTimeMillis()
        if (abs(now - request.timestamp) > 300_000) {
            call.respond(HttpStatusCode.BadRequest, FederationApiResponse(
                success = false,
                data = null,
                error = "Request timestamp too old",
                timestamp = now
            ))
            return@post
        }
        
        // Verify signature
        val publicKey = FederationCrypto.pemToPublicKey(request.publicKey)
        val dataToVerify = "${request.serverId}|${request.timestamp}|${request.proposedScopes}"
        val isValid = FederationCrypto.verify(dataToVerify, request.signature, publicKey)
        
        if (!isValid) {
            call.respond(HttpStatusCode.Unauthorized, FederationApiResponse(
                success = false,
                data = null,
                error = "Invalid signature",
                timestamp = now
            ))
            return@post
        }
        
        // Process handshake
        val acceptedScopes = determineAcceptedScopes(request.proposedScopes)
        val response = federationService.acceptHandshake(request, acceptedScopes)
        
        call.respond(HttpStatusCode.OK, FederationApiResponse(
            success = true,
            data = response,
            error = null,
            timestamp = now
        ))
    } catch (e: Exception) {
        // Handle errors...
    }
}
```

#### Step 5: Register Routes in Application

Update `Application.kt`:

```kotlin
import org.barter.features.federation.di.federationModule
import org.barter.features.federation.routes.federationRoutes
import org.barter.features.federation.routes.federationAdminRoutes

fun Application.module(testing: Boolean = false) {
    // ... existing code ...
    
    install(Koin) {
        SLF4JLogger()
        modules(
            authenticationModule,
            profilesModule,
            categoriesModule,
            healthCheckModule,
            relationshipsModule,
            postingsModule,
            federationModule  // Add this
        )
    }
    
    // ... existing code ...
}
```

Update `RouteManager.kt`:

```kotlin
import org.barter.features.federation.routes.federationRoutes
import org.barter.features.federation.routes.federationAdminRoutes

fun Application.routes() {
    routing {
        // ... existing routes ...
        federationRoutes()
        federationAdminRoutes()
    }
}
```

### Phase 3: User Federation

#### Key Tasks:
1. Create `dao/FederatedUserDao.kt`
2. Implement user sync endpoint
3. Implement federated user search (nearby profiles)
4. Add background sync job
5. Add cache expiration logic
6. Privacy controls (user opt-in/opt-out)

### Phase 4: Posting Federation

#### Key Tasks:
1. Create `dao/FederatedPostingDao.kt`
2. Implement posting sync endpoint
3. Federated posting search
4. Handle posting expirations
5. Image URL handling across servers

### Phase 5: Chat Federation

#### Key Tasks:
1. Message relay endpoint
2. Update `ConnectionManager` for federation awareness
3. Cross-server message routing
4. Maintain E2E encryption across servers
5. Offline message handling for federated users

### Phase 6: Production Readiness

#### Key Tasks:
1. Redis integration for distributed caching
2. Message queue (RabbitMQ/Kafka) for reliability
3. Rate limiting per federated server
4. Monitoring and metrics (Prometheus)
5. Admin dashboard for federation management
6. Automated tests (unit, integration, e2e)

## Testing Strategy

### Unit Tests

```kotlin
class FederationCryptoTest {
    @Test
    fun `test key generation`() {
        val keyPair = FederationCrypto.generateKeyPair()
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)
    }
    
    @Test
    fun `test signature creation and verification`() {
        val keyPair = FederationCrypto.generateKeyPair()
        val data = "test data"
        val signature = FederationCrypto.sign(data, keyPair.private)
        assertTrue(FederationCrypto.verify(data, signature, keyPair.public))
    }
}
```

### Integration Tests

```kotlin
class FederationHandshakeTest {
    @Test
    fun `test handshake flow`() = testApplication {
        // Setup two server instances
        // Perform handshake
        // Verify servers are saved in database
        // Verify audit logs
    }
}
```

### End-to-End Tests

Set up two separate server instances and test:
- Handshake establishment
- User search across servers
- Message relay
- Trust revocation

## Security Checklist

- [ ] All federation requests are signed
- [ ] Timestamps are validated (prevent replay attacks)
- [ ] Private keys are encrypted at rest
- [ ] Private keys are never exposed via API
- [ ] Rate limiting per federated server
- [ ] Audit logging for all federation events
- [ ] HTTPS required for all server-to-server communication
- [ ] GDPR compliance (data deletion, user consent)

## Configuration

Add to `application.conf`:

```hocon
federation {
    enabled = true
    serverUrl = ${?FEDERATION_SERVER_URL}
    serverName = ${?FEDERATION_SERVER_NAME}
    adminContact = ${?FEDERATION_ADMIN_CONTACT}
    
    security {
        keySize = 2048
        signatureAlgorithm = "SHA256withRSA"
        timestampToleranceMs = 300000
    }
    
    sync {
        cacheExpiryDays = 30
        syncIntervalHours = 24
    }
    
    audit {
        retentionDays = 365
    }
}
```

## Common Pitfalls to Avoid

1. **Don't forget timestamp validation** - Prevents replay attacks
2. **Always verify signatures** - Never trust incoming data
3. **Encrypt private keys at rest** - Use encryption before storing
4. **Handle network failures gracefully** - Circuit breakers, retries
5. **Log everything** - Audit trail is critical for debugging
6. **Test with real network latency** - Don't assume instant responses
7. **Consider data privacy** - Get user consent for federation

## Debugging Tips

### Check if server identity is initialized:
```sql
SELECT * FROM local_server_identity;
```

### List all federated servers:
```sql
SELECT server_id, server_name, trust_level, is_active 
FROM federated_servers;
```

### View recent federation events:
```sql
SELECT event_type, action, outcome, timestamp 
FROM federation_audit_log 
ORDER BY timestamp DESC 
LIMIT 50;
```

### Check cached federated users:
```sql
SELECT remote_user_id, origin_server_id, last_updated, expires_at
FROM federated_users;
```

## Resources

- **Cryptography**: Existing `CryptoUtils.kt`, `SecurityUtils.kt`, `SignatureVerification.kt`
- **WebSocket**: `features/chat/` for message relay infrastructure
- **Background Jobs**: `features/postings/tasks/PostingExpirationTask.kt` as example
- **Federation Protocols**: ActivityPub, Matrix Protocol for inspiration

## Next Steps

1. Run migration: `./gradlew flywayMigrate` (or your migration process)
2. Create DAO implementation
3. Implement FederationService core methods
4. Add signature verification middleware
5. Complete handshake endpoint
6. Test with local servers on different ports
7. Move to user federation

## Questions?

Refer to `README.md` in this directory for architectural overview and detailed documentation.
