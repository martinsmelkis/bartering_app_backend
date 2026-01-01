# Barter App Backend

A modern, feature-rich backend for a decentralized barter and skill-exchange platform. 
Built with Kotlin and Ktor, this application enables users to discover, connect, and exchange skills, 
services, and items within their local communities through AI-powered matching, semantic search, 
and intelligent match notifications.

## 🎯 Use Cases

### Primary Use Cases

**1. Exchange of Items and Skills**
- Users add their overall attributes - Interests and Offers, and their Interest level of 7 generalized Categories
- Users can offer skills they possess (e.g., guitar lessons, web design, language tutoring)
- Search for skills they want to learn from nearby community members
- AI-powered matching connects Users based on interests, what they offer, and location

**2. Service & Item Bartering**
- Post offers for services or physical items available for trade
- Discover nearby offers through location-based search
- Semantic matching suggests relevant trades based on user profiles

**3. Attribute match and Posting Notifications**
- Automatic notifications when matching items are posted
- Smart matching algorithm with keyword, price, and location filtering
- Beautiful HTML emails and push notifications
- Daily digest option for match summaries
- Full user control over notification preferences

**4. Community Building**
- Real-time encrypted chat for coordinating exchanges
- Relationship management (connections, blocked users)
- Location-aware discovery to build local networks

**5. Decentralized Federation** (In Development)
- Server-to-server federation for connecting independent barter communities
- Cross-server user discovery and posting search
- Trust-based federation with cryptographic verification

## 🏗️ Architecture

### Core Architecture Principles

- **Feature-Based Organization**: Each feature is self-contained with its own models, DAOs, routes, and services
- **Dependency Injection**: Koin for clean, testable dependency management
- **SOLID Principles**: Interfaces and implementations separated for maintainability
- **Attribute-Based Model**: Flexible system where skills, interests, and items are unified as "attributes"

### Key Architectural Patterns

**1. Attribute System**
The application centers around a flexible attribute-based model:
- **`attributes` table**: Master dictionary of all skills, interests, and items
- **`user_attributes` table**: Links users to attributes with type (`SEEKING`, `PROVIDING`, `SHARING`)
- **Semantic Embeddings**: Each attribute has a 1024-dimensional vector for AI-powered matching
- **Categorization**: Flexible many-to-many relationship between attributes and categories

**2. AI-Powered Profile Generation**
- Users complete onboarding questions about their personality and interests
- AI embedding model analyzes responses and matches against master attribute list
- System automatically populates user profile with relevant attributes
- Continuous similarity scoring enables intelligent user and posting discovery

**3. Geospatial Intelligence**
- PostGIS extension for efficient location-based queries
- User profiles store locations as `POINT` geometry types
- Nearby user and posting discovery with configurable radius
- Distance-based sorting and filtering

**4. Real-Time Communication**
- WebSocket-based chat with end-to-end encryption support
- Offline message storage and delivery
- Connection management with in-memory caching (Redis-ready for scaling)
- Public key caching for cryptographic operations

## 🛠️ Technology Stack

### Core Framework & Language
- **Kotlin 2.2.0**: Modern, type-safe JVM language
- **Ktor 3.2.3**: Lightweight, asynchronous web framework
- **Netty**: High-performance embedded server
- **JDK 21** (Amazon Corretto): Java runtime

### Database & ORM
- **PostgreSQL 42.7.5**: Primary relational database
- **PostGIS**: Geospatial extension for location queries
- **pgvector 0.1.4**: Vector similarity search for embeddings
- **Exposed 0.61.0**: Type-safe SQL framework
- **HikariCP 5.1.0**: High-performance connection pooling
- **Flyway 11.16.0**: Database migrations

### AI & Embeddings
- **Ollama**: Local AI model integration for embeddings and semantic matching
- **Custom embedding pipeline**: Converts text to 1024-dimensional vectors
- **Semantic similarity**: Cosine similarity for matching users, attributes, and postings

### Dependency Injection & Testing
- **Koin 4.1.0**: Lightweight DI framework for Kotlin
- **JUnit 5**: Modern testing framework
- **MockK**: Mocking library for Kotlin
- **Ktor Test**: Framework-specific testing utilities

### Security & Cryptography
- **BouncyCastle 1.70**: Cryptographic operations and signature verification
- **Custom Signature Verification**: Public key cryptography for request authentication
- **PGP**: End-to-end encryption support

### Cloud Services
- **Firebase Admin SDK 9.3.0**: Authentication and notifications
- **Google Cloud Storage 2.30.1**: File and image storage
- **Cloud-ready architecture**: Scalable storage for user-generated content

### Serialization & Content Negotiation
- **Jackson 2.20.1**: JSON serialization with date/time support
- **Kotlinx Serialization 1.8.1**: Native Kotlin serialization
- **Gson**: Additional JSON support for specific use cases

### Build & Development
- **Gradle 8**: Build automation with Kotlin DSL support
- **Shadow JAR**: Fat JAR creation for deployment
- **Docker**: Container support (configuration included)

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
- Phase 1 (Complete): Database schema, crypto utilities, and foundation
- Server-to-server handshake protocol
- Cross-server user and posting discovery
- Federated chat message relay
- Trust levels and scope-based permissions
- Audit logging for security compliance

## 🗄️ Database Schema Overview

### Core Tables
- **`user_registration_data`**: Authentication and identity
- **`user_profiles`**: Profile data with PostGIS location
- **`attributes`**: Master skills/interests dictionary with embeddings
- **`user_attributes`**: User-to-attribute relationships with type
- **`categories`**: Attribute categorization
- **`attribute_categories_link`**: Many-to-many category relationships

### Marketplace Tables
- **`user_postings`**: Offers and interests with semantic embeddings
- **`posting_attributes_link`**: Posting-to-attribute relationships

### Communication Tables
- **`offline_messages`**: Encrypted message storage for offline delivery
- **`encrypted_files`**: Secure file metadata and storage

### Relationships Tables
- **`user_relationships`**: Connection and blocking management

### Wishlist Tables (V3 Migration)
- **`wishlist_items`**: User wishlist items with search criteria
- **`wishlist_matches`**: Match tracking between wishes and postings
- **`wishlist_notification_settings`**: User notification preferences

### Notification Tables (To Be Created)
- **`user_notification_preferences`**: Email/push preferences per user
- **`user_push_tokens`**: Device tokens for push notifications
- **`notification_category_preferences`**: Per-category notification settings

### Federation Tables (In Development)
- **`local_server_identity`**: Server cryptographic identity
- **`federated_servers`**: Trusted server registry
- **`federated_users`**: Cached remote user profiles
- **`federated_postings`**: Cached remote postings
- **`federation_audit_log`**: Security audit trail

## 🚀 Getting Started

### Prerequisites
- JDK 21 (Amazon Corretto recommended)
- PostgreSQL 14+ with PostGIS and pgvector extensions
- Gradle 8+
- Ollama (for AI embeddings)

### Installation

1. **Clone the repository**
```bash
git clone https://github.com/martinsmelkis/bartering_app_backend
cd bartering_app_backend
```

2. **Update configuration**
Configure environment variables in docker-compose and application settings.

3. **Build the application**
```bash
docker-compose up --build
```

The server will start on `http://0.0.0.0:8081`

## 📡 API Endpoints

### Public Endpoints
- `GET /public-api/v1/healthCheck` - Server health status
- `POST /public-api/v1/authentication/createUser` - User registration

### Authenticated Endpoints (with Signature Verification)
- `GET /api/v1/authentication/userInfo` - Current user info
- `DELETE /api/v1/authentication/user/{userId}` - Delete user and all associated data (requires signature match)
- `GET /api/v1/profiles/nearby` - Discover nearby users
- `POST /api/v1/ai/parse-onboarding` - AI profile generation
- `POST /api/v1/postings` - Create marketplace posting
- `GET /api/v1/postings/nearby` - Nearby postings
- `GET /api/v1/postings/search` - Semantic posting search
- `POST /api/v1/postings/matches` - Get personalized matches

### WebSocket Endpoints
- `WS /chat` - Real-time messaging

### Federation Endpoints (In Development)
- `GET /federation/v1/server-info` - Public server information
- `POST /federation/v1/handshake` - Establish federation
- `POST /federation/v1/sync-users` - User data synchronization

## 🔒 Security Features

- **Public Key Authentication**: Custom signature verification for requests
- **End-to-End Encryption**: Client-side message encryption
- **PGP/GPG Support (in development)**: For secure data exchange
- **Rate Limiting Ready**: Architecture supports rate limiting implementation
- **Audit Logging**: Federation feature includes comprehensive audit trails
- **CORS Configuration**: Flexible cross-origin resource sharing

## 🧪 Testing

The application includes comprehensive testing infrastructure:

- **Unit Tests**: MockK-based component testing
- **Integration Tests**: Database and API endpoint testing
- **Archetype Tests**: User generation and similarity matching tests
- **Test Data Generation**: Automated test user creation with realistic profiles

Run tests:
```bash
./gradlew test
```

## 📈 Performance Optimizations

- **Connection Pooling**: HikariCP for efficient database connections
- **Vector Indexing**: HNSW indexes for fast similarity searches
- **GIN Indexes**: Full-text search with pg_trgm
- **Public Key Caching**: In-memory cache reduces database lookups by ~90%
- **Background Tasks**: Automatic cleanup for expired postings and messages, inactive users
- **Lazy Loading**: Efficient data fetching patterns

## 🔮 Future Enhancements

- [ ] Batch notification digests (daily/weekly summaries)
- [ ] Profile attribute matching (notify when users add matching skills)
- [ ] Price drop alerts for existing matches
- [ ] Postings/match templates for common items
- [ ] Complete email provider SDK implementations

### Infrastructure
- [ ] Complete server federation implementation (Phases 2-6)
- [ ] Redis integration for distributed caching and connection management
- [ ] Message queue (RabbitMQ/Kafka) for reliable message delivery
- [ ] Advanced analytics and recommendation engine
- [ ] User reputation and rating system
- [ ] Multi-language support (localization framework in place)
- [ ] Admin dashboard for moderation
- [ ] Advanced search filters and saved searches

## 📚 Documentation

Additional documentation available in the codebase:

### Feature Documentation
- `src/org/barter/features/notifications/README.md` - **Notifications infrastructure guide**
- `DOCS/NOTIFICATIONS_IMPLEMENTATION.md` - **Notification system implementation**
- `src/org/barter/features/postings/README.md` - Posting system details
- `src/org/barter/features/chat/README.md` - Chat implementation guide
- `src/org/barter/features/encryptedfiles/FILE_SHARING_GUIDE.md` - File sharing docs
- `src/org/barter/features/relationships/RELATIONSHIPS_API.md` - Relationship management

### API Documentation
- `DOCS/USER_DELETION_API.md` - User account deletion and GDPR compliance

### Architecture Documentation
- `DOCS/FEDERATION_FEATURE_SUMMARY.md` - Complete federation architecture
- `DOCS/V2__Federation.sql` - Federation database schema

## 🤝 Contributing

This project follows SOLID principles and clean architecture patterns. When contributing:
1. Maintain feature-based organization
2. Use dependency injection via Koin
3. Write comprehensive tests
4. Follow existing code style and patterns
5. Update documentation for new features

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

Copyright 2026 Barter App Backend Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

---
