# User Postings Feature

## Overview

The User Postings feature allows users to create, manage, and discover offers and interests/needs
within the barter community. Postings can include images, descriptions, optional monetary values,
and expiration dates. The system uses semantic embeddings to enable intelligent matching and search
capabilities.

## Data Model

### UserPosting

- **id**: Unique identifier (UUID)
- **userId**: The user who created the posting
- **title**: Short title/headline (max 255 chars)
- **description**: Detailed description
- **value**: Optional estimated monetary value
- **expiresAt**: Optional expiration timestamp
- **imageUrls**: List of image URLs
- **isOffer**: Boolean - true for offers, false for interests/needs
- **status**: ACTIVE, EXPIRED, DELETED, or FULFILLED
- **attributes**: List of associated attributes/tags
- **createdAt**: Creation timestamp
- **updatedAt**: Last update timestamp

### PostingStatus

- **ACTIVE**: Currently visible and searchable
- **EXPIRED**: Past expiration date, no longer shown
- **DELETED**: Soft-deleted by user
- **FULFILLED**: Successfully completed/traded

## API Endpoints

All authenticated endpoints use signature verification. Base path: `/api/v1/postings`

### Create Posting

```
POST /api/v1/postings
```

**Authentication**: Required (signature verification)

**Request Body**:

```json
{
  "title": "Vintage bicycle for trade",
  "description": "Classic 1980s road bike in great condition...",
  "value": 150.0,
  "expiresAt": "2024-12-31T23:59:59Z",
  "imageUrls": ["https://example.com/image1.jpg"],
  "isOffer": true,
  "attributes": [
    {"attributeId": "cycling", "relevancy": 1.0},
    {"attributeId": "vintage_items", "relevancy": 0.8}
  ]
}
```

### Update Posting

```
PUT /api/v1/postings/{postingId}
```

**Authentication**: Required (must be posting owner)

### Delete Posting

```
DELETE /api/v1/postings/{postingId}
```

**Authentication**: Required (must be posting owner)

### Get Specific Posting

```
GET /api/v1/postings/{postingId}
```

**Authentication**: Not required

### Get Current User's Postings

```
POST /api/v1/postings/user/me?includeExpired=false
```

**Authentication**: Required

### Get User's Postings (by userId)

```
GET /api/v1/postings/user/{userId}
```

**Authentication**: Not required (only returns active postings)

### Get Nearby Postings

```
GET /api/v1/postings/nearby?latitude=48.8566&longitude=2.3522&radiusMeters=5000&isOffer=true&limit=50
```

**Authentication**: Optional (can pass excludeUserId to filter out own postings)

**Query Parameters**:

- `latitude` (required): Latitude coordinate
- `longitude` (required): Longitude coordinate
- `radiusMeters` (optional, default: 5000): Search radius in meters
- `isOffer` (optional): Filter by offer (true) or interest (false)
- `excludeUserId` (optional): Exclude postings from specific user
- `limit` (optional, default: 50): Maximum results

### Search Postings

```
GET /api/v1/postings/search?q=bicycle&latitude=48.8566&longitude=2.3522&radiusMeters=10000&limit=50
```

**Authentication**: Not required

Uses semantic search to find relevant postings based on keywords.

**Query Parameters**:

- `q` (required): Search query text
- `latitude` (optional): Filter by location
- `longitude` (optional): Must be provided with latitude
- `radiusMeters` (optional): Search radius if location provided
- `isOffer` (optional): Filter by type
- `limit` (optional, default: 50): Maximum results

### Get Matching Postings

```
POST /api/v1/postings/matches?latitude=48.8566&longitude=2.3522&radiusMeters=10000&limit=50
```

**Authentication**: Required

Finds postings that semantically match the user's profile (interests and offers).

**Query Parameters**:

- `latitude` (optional): Filter by location
- `longitude` (optional): Must be provided with latitude
- `radiusMeters` (optional): Search radius if location provided
- `limit` (optional, default: 50): Maximum results

## Features

### Semantic Embeddings

Each posting automatically generates a semantic embedding from its title and description. This
enables:

- Intelligent search that understands context and related terms
- Matching postings to user profiles based on semantic similarity
- Discovery of relevant postings even without exact keyword matches

### Location-Based Discovery

Postings are linked to user locations, enabling:

- Nearby posting discovery using PostGIS spatial queries
- Distance-based sorting
- Radius filtering

### Attribute Tagging

Postings can be tagged with multiple attributes (skills, interests, categories):

- Enables categorization and filtering
- Links postings to the broader attribute system
- Supports relevancy scoring per attribute

### Progressive Image Loading

The system automatically generates thumbnail versions of uploaded images for optimal performance:

- **Thumbnail**: 300x300px versions for list/grid views (fast loading)
- **Full Resolution**: Original quality images for detail views
- **Automatic Generation**: Both versions created on upload using Thumbnailator
- **Smart Caching**: Aggressive cache headers for better client-side performance

#### Image Serving API

Images are served with a `size` query parameter:

```
GET /api/v1/images/{userId}/{fileName}?size=thumb   # 300x300px thumbnail
GET /api/v1/images/{userId}/{fileName}?size=full    # Full resolution
```

**Accepted size values**: `thumb`, `thumbnail`, `full`, `original`

**Client Implementation Strategy**:
1. Display thumbnails in postings lists (lightweight, fast scrolling)
2. Fetch full resolution only when user clicks/expands image
3. Cache both versions locally for offline access

**Storage Structure**:
```
uploads/images/
  └── {userId}/
      ├── {uuid}_thumb.jpg   # Thumbnail version
      └── {uuid}_full.jpg    # Full resolution
```

**Cache Headers**:
- Thumbnails: 1 year cache (`max-age=31536000`)
- Full images: 30 days cache (`max-age=2592000`)
- ETag support for efficient revalidation

### Automatic Expiration

- Background task runs hourly to mark expired postings
- Expired postings are automatically excluded from search results
- Users can set custom expiration dates for time-sensitive offers

### Status Management

- **ACTIVE**: Normal state, appears in searches
- **EXPIRED**: Automatically set when expires_at passes
- **DELETED**: Soft delete - preserved in database
- **FULFILLED**: Manually set by user when trade completes

## Database Schema

### user_postings Table

```sql
CREATE TABLE user_postings (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id),
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    value DECIMAL(10, 2),
    expires_at TIMESTAMPTZ,
    image_urls JSONB DEFAULT '[]'::jsonb,
    is_offer BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    embedding VECTOR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### posting_attributes_link Table

```sql
CREATE TABLE posting_attributes_link (
    posting_id VARCHAR(36) NOT NULL REFERENCES user_postings(id),
    attribute_id VARCHAR(100) NOT NULL REFERENCES attributes(attribute_key),
    relevancy DECIMAL(5, 4) NOT NULL DEFAULT 1.0,
    PRIMARY KEY (posting_id, attribute_id)
);
```

## Usage Examples

### Creating an Offer

```kotlin
val request = UserPostingRequest(
    title = "Guitar lessons",
    description = "I offer beginner to intermediate guitar lessons",
    isOffer = true,
    attributes = listOf(
        PostingAttributeDto("music", 1.0),
        PostingAttributeDto("teaching", 0.9)
    )
)
postingDao.createPosting(userId, request)
```

### Searching for Offers

```kotlin
val results = postingDao.searchPostings(
    searchText = "guitar lessons",
    latitude = userLat,
    longitude = userLon,
    radiusMeters = 10000.0,
    isOffer = true
)
```

### Finding Matches

```kotlin
val matches = postingDao.getMatchingPostings(
    userId = currentUserId,
    latitude = userLat,
    longitude = userLon,
    radiusMeters = 5000.0
)
```

## Background Tasks

### PostingExpirationTask

Automatically marks expired postings every hour. Initialize in Application.kt:

```kotlin
val postingDao: UserPostingDao by inject(UserPostingDao::class.java)
val expirationTask = PostingExpirationTask(postingDao)
expirationTask.start(GlobalScope) // Or use appropriate CoroutineScope
```

## Future Enhancements

- [x] Image upload and storage integration with progressive loading
- [ ] Posting analytics (views, interactions)
- [ ] User ratings and reviews on completed trades
- [ ] Posting templates for common offer types
- [ ] Saved searches and alerts
- [ ] Posting recommendations based on user behavior
- [ ] Negotiation/messaging integration
- [ ] Report/flag inappropriate postings
