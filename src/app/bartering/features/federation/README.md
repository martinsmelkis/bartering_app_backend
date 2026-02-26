# Federation Feature

This package implements the foundation for server-to-server federation, allowing multiple 
independent barter app instances to communicate and share data securely.

## Status: ✅ ACTIVE IMPLEMENTATION

Federation is now functional for core workflows: server handshake, trust scopes, user discovery, federated search, and cross-server messaging (including public-key exchange and offline delivery). Ongoing work focuses on production hardening and operational tooling.

## Architecture Overview

### Federation Models

The system supports both **hybrid** and **fully federated** approaches:

- **Hybrid Model**: Servers selectively share data through a registry or opt-in mechanism
- **Fully Federated Model**: Servers automatically sync and share data based on trust agreements

### Core Components

#### 1. Database Tables (`db/`)

- **`FederatedServersTable`**: Stores trusted federated servers with cryptographic keys and scopes
- **`LocalServerIdentityTable`**: This server's identity and cryptographic keys
- **`FederatedUsersTable`**: Cached user profiles from federated servers (includes public keys for chat)
- **`FederatedPostingsTable`**: Cached postings from federated servers
- **`FederationAuditLogTable`**: Audit trail of all federation activities

#### 2. Data Models (`model/`)

- **`FederatedServer`**: Represents a remote server instance
- **`LocalServerIdentity`**: This server's identity and keys
- **`FederationScope`**: Defines what data is shared (users, postings, chat, etc.)
- **`TrustLevel`**: FULL, PARTIAL, PENDING, BLOCKED
- **`FederationProtocol`**: Request/response DTOs for server-to-server communication

#### 3. Cryptographic Layer (`crypto/`)

- **`FederationCrypto`**: Key generation, signature creation/verification
  - RSA key pair generation
  - PEM format conversion
  - Signature signing and verification
  - Data hashing for integrity checks

#### 4. Service Layer (`service/`)

- **`FederationService`**: Business logic for federation operations
  - Server identity management
  - Handshake protocol
  - Trust management
  - Signature verification
  - Message relay to federated servers

#### 5. API Routes (`routes/`)

- **`/federation/v1/*`**: Server-to-server endpoints
  - `/server-info` - Public server information
  - `/handshake` - Establish federation
  - `/sync-users` - User data synchronization
  - `/users/nearby` - Geolocation-based search
  - `/messages/relay` - Cross-server messaging with sender public key propagation
- `/postings/search` - Cross-server posting search

- **`/api/v1/federation/admin/*`**: Admin management endpoints
  - `/initialize` - Set up local server identity
  - `/servers` - List federated servers
  - `/handshake` - Initiate federation with another server
  - `/servers/{id}/trust` - Update trust levels

## Federation Workflow

### Initial Setup

1. **Generate Server Identity**
   - Admin initializes the server with a unique ID and key pair
   - Server identity is saved to `LocalServerIdentityTable`

### Establishing Federation

2. **Handshake Process**
   ```
   Server A                          Server B
      |                                 |
      |---> GET /server-info ---------->|
      |<--- Server B public info -------|
      |                                 |
      |---> POST /handshake ----------->|
      |     (signed request)            |
      |                                 |
      |     [Server B verifies]         |
      |                                 |
      |<--- Handshake response ---------|
      |     (signed response)           |
      |                                 |
   [Both servers save each other to FederatedServersTable]
   ```

3. **Trust & Scopes**
   - Admin configures trust level (FULL, PARTIAL, PENDING, BLOCKED)
   - Defines scopes: which data to share
     - `users`: User profiles
     - `postings`: Marketplace postings
     - `chat`: Cross-server messaging
     - `geolocation`: Location data
     - `attributes`: Skills/interests

### Data Synchronization

4. **User Federation**
   - Server A requests: `POST /sync-users`
   - Server B returns user profiles (filtered by scopes)
   - Server A caches users in `FederatedUsersTable`
   - Periodic sync keeps data fresh

5. **Cross-Server Search**
   - User searches for nearby profiles
   - Local server queries: local DB + federated servers
   - Results merged and displayed with origin indicator

6. **Message Relay**
   - User A (Server 1) → User B (Server 2)
   - Message sent to Server 1 → relayed to Server 2 → delivered to User B
   - Sender public key is included in relay for decryption
   - Offline messages store sender public key for later delivery

## Security Features

### Cryptographic Trust

- **RSA Key Pairs**: Each server has unique keys
- **Signature Verification**: All requests/responses are signed
- **Agreement Hashing**: Federation terms are cryptographically hashed
- **Timestamp Validation**: Reject stale requests

### Privacy Controls

- **Opt-in Federation**: Users choose to enable federation
- **Scope Permissions**: Granular control over shared data
- **Data Retention**: Configurable cache expiration
- **Audit Logging**: Complete trail of federation activities

### Protection Mechanisms

- **Trust Levels**: Graduated trust model
- **Rate Limiting**: Per-server request limits (TODO)
- **Revocation**: Instant trust revocation capability
- **GDPR Compliance**: Right to be forgotten across federation

## Implementation Roadmap

### Phase 1: Foundation ✅
- [x] Database schema design
- [x] Data models and DTOs
- [x] Cryptographic utilities
- [x] Service interface definitions

### Phase 2: Core Federation ✅
- [x] DAO layer
- [x] Local server identity generation
- [x] Handshake protocol implementation
- [x] Signature verification middleware
- [x] Trust management logic

### Phase 3: User Federation ✅
- [x] User sync endpoints
- [x] Federated user search (keyword + geolocation)
- [x] Privacy-safe profile sharing
- [x] Federated user cache with public keys

### Phase 4: Posting Federation ✅
- [x] Federated posting search
- [x] Posting data sharing with scopes

### Phase 5: Chat Federation ✅
- [x] Message relay infrastructure
- [x] Cross-server message delivery
- [x] Sender public key propagation
- [x] Offline message handling with sender key

### Phase 6: Production Readiness (TODO)
- [ ] Redis integration for distributed caching
- [ ] Message queue for reliability
- [ ] Monitoring and metrics
- [ ] Rate limiting
- [ ] Admin dashboard

## Configuration

### Environment Variables (Future)

```properties
# Federation Settings
FEDERATION_ENABLED=true
FEDERATION_SERVER_URL=https://mybarter.example.com
FEDERATION_SERVER_NAME=My Barter Hub
FEDERATION_ADMIN_CONTACT=admin@example.com

# Security
FEDERATION_KEY_SIZE=2048
FEDERATION_SIGNATURE_ALGORITHM=SHA256withRSA
FEDERATION_TIMESTAMP_TOLERANCE_MS=300000

# Data Retention
FEDERATION_CACHE_EXPIRY_DAYS=30
FEDERATION_AUDIT_LOG_RETENTION_DAYS=365
```

## API Examples

### Get Server Info

```bash
GET /federation/v1/server-info

Response:
{
  "success": true,
  "data": {
    "serverId": "123e4567-e89b-12d3-a456-426614174000",
    "serverUrl": "https://barter.example.com",
    "serverName": "Portland Barter Hub",
    "publicKey": "-----BEGIN PUBLIC KEY-----\n...",
    "protocolVersion": "1.0"
  },
  "timestamp": 1640000000000
}
```

### Initiate Handshake

```bash
POST /federation/v1/handshake
Content-Type: application/json

{
  "serverId": "...",
  "serverUrl": "https://mybarter.example.com",
  "serverName": "My Barter Hub",
  "publicKey": "-----BEGIN PUBLIC KEY-----\n...",
  "protocolVersion": "1.0",
  "proposedScopes": {
    "users": true,
    "postings": true,
    "chat": false,
    "geolocation": true,
    "attributes": true
  },
  "timestamp": 1640000000000,
  "signature": "base64_encoded_signature"
}
```

## Testing Strategy

### Unit Tests (TODO)
- Cryptographic functions
- Signature verification
- Data model serialization
- Service layer logic

### Integration Tests (TODO)
- Handshake flow
- User sync
- Message relay
- Trust management

### End-to-End Tests (TODO)
- Two-server federation scenario
- Cross-server search
- Message delivery
- Trust revocation

## Monitoring & Observability (TODO)

### Metrics to Track
- Active federation connections
- Sync success/failure rates
- Message relay latency
- Cache hit/miss ratios
- Signature verification failures

### Alerts
- Handshake failures
- Sync errors
- Trust level changes
- Signature verification failures
- Rate limit violations

## Related Documentation

- `/DOCS/CHAT_IMPLEMENTATION_CHECKLIST.md` - Chat infrastructure
- `/DOCS/SECURITY_AUDIT_SUMMARY.md` - Security practices
- `/src/org/barter/utils/SecurityUtils.kt` - Security utilities
- `/src/org/barter/utils/CryptoUtils.kt` - Existing crypto functions

## Contributing

When implementing federation features:

1. **Follow the existing patterns** in other features (profile, postings, chat)
2. **Write comprehensive tests** for cryptographic operations
3. **Document security decisions** in code comments
4. **Update this README** with implementation details
5. **Add migration scripts** for database changes

## Questions & Support

For federation-related questions or design discussions, contact the project maintainer.
