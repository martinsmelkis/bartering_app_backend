# Barter App Backend

A modern, feature-rich backend for a decentralized barter and skill-exchange platform. 
Built with Kotlin and Ktor, this application enables users to discover, connect, and exchange skills, 
services, and items within their local communities through AI-powered matching, semantic search, 
and intelligent match notifications.

Use together with [bartering_app](https://github.com/martinsmelkis/bartering_app).

## 🎯 Capabilities

### Primary Use Cases

**1. Exchange of Items and Skills**
- Users add their overall attributes - Interests and Offers, and their Interest level of 7 generalized Categories
- Users can offer skills they possess (e.g., guitar lessons, web design, language tutoring)
- Search for skills they want to learn from nearby community members
- AI-powered matching connects Users based on interests, what they offer, and location
- Coordinate via real-time encrypted chat
- Manage trust with transactions, reviews, reputation, blocking, and reporting

**2. Service & Item Bartering**
- Post offers for services or physical items available for trade
- Discover nearby offers through location-based search
- Semantic matching suggests relevant trades based on user profiles

**3. AI-Powered Profile Generation**
- Users complete onboarding questions about their personality and interests
- AI embedding model analyzes responses and matches against master attribute list
- System automatically populates user profile with relevant attributes
- Continuous similarity scoring enables intelligent user and posting discovery

**4. Geospatial Intelligence**
- PostGIS extension for efficient location-based queries
- User profiles store locations as `POINT` geometry types
- Nearby user and posting discovery with configurable radius
- Distance-based sorting and filtering

**5. Real-Time Communication**
- WebSocket-based chat with end-to-end encryption support
- Offline message storage and delivery
- Connection management with in-memory caching (Redis-ready for scaling)
- Public key caching for cryptographic operations

**6. Attribute match and Posting Notifications**
- Automatic notifications when matching items are posted
- Smart matching algorithm with keyword, price, and location filtering
- E-mails and push notifications
- Full user control over notification preferences

**7. Community Building**
- Real-time encrypted chat for coordinating exchanges
- Relationship management (connections, blocked users)
- Location-aware discovery to build local networks

**8. Decentralized Federation**
- Server-to-server federation for connecting independent barter communities
- Cross-server user discovery and posting search
- Trust-based federation with cryptographic verification

**9. Safety and compliance**

- Signature-authenticated API access
- GDPR data export + account deletion flows
- Compliance admin routes (legal holds, retention evidence, audit search)
- Retention orchestrator with configurable policies

## 🏗️ Architecture

### Project style

- **Feature-Based Organization**: Each feature is self-contained with its own models, DAOs, routes, and services
- **Dependency Injection**: Koin for clean, testable dependency management
- **SOLID Principles**: Interfaces and implementations separated for maintainability
- **Attribute-Based Model**: Flexible system where skills, interests, and items are unified as "attributes"

### Active backend modules

- Authentication
- AI + attribute parsing
- Analytics (daily activity stats)
- Profiles + presence
- Categories/attributes
- Postings + image upload/serve
- Notifications (email + push + preference routes)
- Chat + encrypted file transfer
- Relationships + block/report moderation signals
- Reviews + reputation + risk analysis
- Migration (email recovery and device transfer)
- Wallet (coins)
- Purchases (premium, coin packs, boosts)
- Compliance admin
- Federation (server-to-server + federation admin)
- Health check

### Dashboards

This repository also includes dashboard subprojects:
- `dashboards/admin_compliance`
- `dashboards/user_moderation`

## 🛠️ Tech Stack

- Kotlin `2.2.0`
- Ktor `3.2.3`
- JDK `21`
- Koin `4.1.0`
- PostgreSQL driver `42.7.5`
- Exposed `1.0.0`
- Flyway `11.16.0`
- HikariCP `5.1.0`
- Firebase Admin SDK `9.3.0`
- Google Cloud Storage `2.30.1`

## 🗄️ Data Model (high level)

- User auth/profile/attributes/categories
- User postings + posting-attribute links
- Chat/offline messages + encrypted files
- User relationships + block/report signals
- Reviews, transactions, reputation, risk-pattern data
- Notification preferences/tokens
- Wallet balances + wallet transactions
- Purchase records + premium entitlements
- Federation identities/servers/cache/audit
- Compliance audit + legal hold tables

## 🚀 Getting Started

### Prerequisites

- JDK 21
- Docker + Docker Compose
- PostgreSQL (if running outside Docker)
- Ollama (for embeddings)

### Run locally

1. Clone repository

```bash
git clone https://github.com/martinsmelkis/bartering_app_backend
cd bartering_app_backend
```

2. Configure environment variables (see `CONFIGURATION_GUIDE.md`)

3. Start services

```bash
docker-compose up --build
```

Main backend default endpoint: `http://0.0.0.0:8081`

## 📦 Feature Overview

### ✅ Implemented Features

**1. Authentication & User Management**
- User registration with public key cryptography
- Custom signature verification for API requests
- User profile management with geospatial data
- Partial GDPR compliance (right to be forgotten)

**2. AI-Powered Profile Building**
- Onboarding questionnaire analysis
- Automatic attribute assignment via semantic matching
- Interest and skill parsing from natural language
- Continuous profile enrichment

**3. Profile Discovery**
- Location-based nearby user search
- Semantic similarity matching between users
- Attribute-based filtering (seeking vs providing)
- Distance-based sorting

**4. User Postings (Marketplace)**
- Create offers (what you provide) and interests (what you seek)
- Image upload and management
- Monetary value estimation (optional)
- Expiration dates and automatic cleanup
- Status management (active, expired, deleted, fulfilled)
- Semantic search across all postings
- Location-based posting discovery
- Matching postings to user profiles

**5. Matching Feature**
- Keyword-based wish items with price ranges and location
- Automatic matching against marketplace postings
- Match notifications via email and push
- Daily digest support
- Status management (active, paused, fulfilled, archived)
- Per-user notification preferences with quiet hours
- Match history and statistics

**6. Notification Infrastructure**
- Multi-provider email support (SendGrid, AWS SES, etc.)
- Multi-provider push support (Firebase FCM, OneSignal, etc.)
- Provider-agnostic interfaces for easy switching
- Rich HTML email templates
- Category-based notification preferences
- Quiet hours and user preference management
- Batch sending and delivery tracking
- Wishlist match notifications with beautiful emails

**7. Real-Time Chat**
- WebSocket-based messaging
- End-to-end encryption support
- Offline message storage and delivery
- Public key caching for performance
- Connection lifecycle management
- Background cleanup tasks

**8. Relationships Management**
- Send and accept connection requests
- Block/unblock users
- Relationship status tracking
- Privacy controls

**9. Categories & Attributes**
- Master attribute dictionary with embeddings
- Flexible categorization system
- User-submitted custom attributes (approval workflow)
- Attribute relevancy scoring

**10. File Sharing** (Encrypted)
- Secure file upload and storage
- Encryption at rest
- Expiration and cleanup
- Integration with Google Cloud Storage

### 🚧 In Development

**Server Federation**
- Audit logging for security compliance

## ⚙️ Configuration

Use the dedicated configuration reference:

- `CONFIGURATION_GUIDE.md`

It documents currently active environment variables (database, Ollama, image storage, Firebase push, Mailjet, retention/compliance, federation bootstrap, dashboards).

## 📡 API Surface (selected)

### Public

- `GET /public-api/v1/healthCheck`
- `POST /public-api/v1/authentication/createUser`

### User-authenticated (signature-verified)

- Authentication/profile management and deletion
- AI parsing routes (`/api/v1/ai/*`)
- Postings routes (`/api/v1/postings/*`)
- Profile discovery/search (`/api/v1/profiles/*`, `/api/v1/similar-profiles`, `/api/v1/complementary-profiles`)
- Presence routes (`/api/v1/users/*/online-status`, batch presence)
- Relationships/block/report routes
- Review/reputation/transaction routes
- Migration routes (`/api/v1/migration/*`)
- Wallet routes (`/api/v1/wallet*`)
- Purchases routes (`/api/v1/purchases/*`)
- Notifications routes (`/api/v1/notifications/*`, `/api/v1/push/*`)

### Compliance admin

- `/api/v1/admin/compliance/legal-holds/*`
- `/api/v1/admin/compliance/retention/*`
- `/api/v1/admin/compliance/audit/search`
- `/api/v1/admin/compliance/dsar/evidence/{userId}`
- `/api/v1/admin/compliance/evidence/summary`

### Federation

- Server-to-server: `/federation/v1/*`
- Federation admin: `/api/v1/federation/admin/*`

### WebSocket

- `WS /chat`

## 🔒 Security Notes

- Request signature verification on protected routes
- CORS enabled only for `ENVIRONMENT=development`
- Compliance admin endpoints require authenticated compliance admin and network allowlist checks
- Federation admin bootstrap protected by init token + allowlist

## 📈 Performance Optimizations
- **Connection Pooling**: HikariCP for efficient database connections
- **Vector Indexing**: HNSW indexes for fast similarity searches
- **GIN Indexes**: Full-text search with pg_trgm
- **Public Key Caching**: In-memory cache reduces database lookups by ~90%
- **Background Tasks**: Automatic cleanup for expired postings and messages, inactive users
- **Lazy Loading**: Efficient data fetching patterns

## 🧪 Testing

Run tests:

```bash
./gradlew test
```

## 📚 Documentation Map

### Root docs

- `CONFIGURATION_GUIDE.md` — current runtime configuration
- `DOCS/DEPLOYMENT_GUIDE_PRODUCTION.md` — production deployment guide
- `DOCS/DEPLOYMENT_GUIDE_VPS_DOCKER.md` — VPS + Docker deployment
- `DOCS/INACTIVE_USER_MANAGEMENT.md` — inactive-user lifecycle
- `DOCS/USER_DELETION_API.md` — account deletion API/GDPR flow

### Feature docs

- `src/app/bartering/features/notifications/README.md`
- `src/app/bartering/features/postings/README.md`
- `src/app/bartering/features/chat/README.md`
- `src/app/bartering/features/encryptedfiles/FILE_SHARING_GUIDE.md`
- `src/app/bartering/features/relationships/RELATIONSHIPS_API.md`
- `src/app/bartering/features/federation/README.md`

## 🤝 Contributing

1. Keep feature-based structure and existing conventions
2. Prefer DI via Koin
3. Add/adjust tests with code changes
4. Update docs for behavioral/config/API changes

## 📄 License

Licensed under Apache License 2.0 — see [LICENSE](LICENSE).
