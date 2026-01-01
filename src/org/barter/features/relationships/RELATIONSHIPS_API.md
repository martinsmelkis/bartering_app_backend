# User Relationships API Documentation

This document describes the user relationships feature that allows users to favorite, friend, block,
and interact with other users in the barter app.

## Relationship Types

The system supports the following relationship types:

| Type | Description | Direction | Auto-created |
|------|-------------|-----------|--------------|
| `favorite` | User has favorited/starred another user for quick access | One-way | No |
| `friend` | Mutual friendship between two users | Mutual | No (requires acceptance) |
| `friend_request_sent` | User has sent a friend request that hasn't been accepted yet | One-way | No |
| `chatted` | Users have exchanged messages | Two-way | Yes (by chat system) |
| `blocked` | User has blocked another user (prevents all interactions) | One-way | No |
| `hidden` | User has hidden another user from search/discovery | One-way | No |
| `reported` | User has reported another user for moderation | One-way | No |
| `traded` | Users have completed a successful barter/trade | Two-way | Yes (by trade system) |
| `trade_interested` | User is interested in trading with another user | One-way | No |

## API Endpoints

All endpoints require authentication via signature verification (X-User-ID, X-Timestamp, X-Signature
headers).

### 1. Create Relationship

**POST** `/api/v1/relationships/create`

Creates a new relationship between two users.

**Request Body:**

```json
{
  "fromUserId": "user-uuid-1",
  "toUserId": "user-uuid-2",
  "relationshipType": "favorite"
}
```

**Response:**

```json
{
  "success": true
}
```

**Notes:**

- User can only create relationships for themselves (fromUserId must match authenticated user)
- Cannot create relationships with yourself
- Cannot interact with users who have blocked you (except to block them back)
- For friend requests, use type `friend_request_sent` - requires acceptance by the other user

---

### 2. Remove Relationship

**POST** `/api/v1/relationships/remove`

Removes an existing relationship.

**Request Body:**

```json
{
  "fromUserId": "user-uuid-1",
  "toUserId": "user-uuid-2",
  "relationshipType": "favorite"
}
```

**Response:**

```json
{
  "success": true
}
```

---

### 3. Get User Relationships

**GET** `/api/v1/relationships/{userId}`

Retrieves all relationships for a user, grouped by type.

**Response:**

```json
{
  "userId": "user-uuid-1",
  "favorites": ["user-uuid-2", "user-uuid-3"],
  "friends": ["user-uuid-4", "user-uuid-5"],
  "friendRequestsSent": ["user-uuid-6"],
  "friendRequestsReceived": ["user-uuid-7"],
  "chattedWith": ["user-uuid-8"],
  "blocked": ["user-uuid-9"],
  "hidden": [],
  "traded": ["user-uuid-10"],
  "tradeInterested": []
}
```

**Notes:**

- Users can only view their own relationships
- `friendRequestsReceived` is derived from other users' `friend_request_sent` to this user

---

### 4. Get Relationships with Profiles

**GET** `/api/v1/relationships/{userId}/{type}`

Retrieves relationships of a specific type with detailed user profile information.

**Path Parameters:**

- `userId`: The user ID
- `type`: Relationship type (e.g., `favorite`, `friend`, `blocked`)

**Response:**

```json
[
  {
    "userId": "user-uuid-2",
    "userName": "John Doe",
    "relationshipType": "favorite",
    "createdAt": "2024-12-13T10:30:00Z",
    "latitude": 48.8566,
    "longitude": 2.3522,
    "isMutual": false
  }
]
```

**Notes:**

- `isMutual` indicates if the other user also has the same relationship type with you
- Useful for displaying friend lists, favorites with profile info, etc.

---

### 5. Accept Friend Request

**POST** `/api/v1/relationships/friend-request/accept`

Accepts a pending friend request.

**Request Body:**

```json
{
  "userId": "user-uuid-1",
  "friendUserId": "user-uuid-2"
}
```

**Response:**

```json
{
  "success": true
}
```

**What happens:**

1. The `friend_request_sent` relationship is removed
2. A mutual `friend` relationship is created for both users

---

### 6. Reject Friend Request

**POST** `/api/v1/relationships/friend-request/reject`

Rejects a pending friend request.

**Request Body:**

```json
{
  "userId": "user-uuid-1",
  "friendUserId": "user-uuid-2"
}
```

**Response:**

```json
{
  "success": true
}
```

**What happens:**

- The `friend_request_sent` relationship is simply removed

---

### 7. Get Relationship Statistics

**GET** `/api/v1/relationships/{userId}/stats`

Retrieves statistics about a user's relationships.

**Response:**

```json
{
  "userId": "user-uuid-1",
  "totalFriends": 15,
  "totalTrades": 8,
  "pendingFriendRequests": 3
}
```

**Notes:**

- This endpoint can be called for any user (public information)
- Useful for displaying user profiles

---

### 8. Check Relationship Existence

**GET** `/api/v1/relationships/check?fromUserId={userId1}&toUserId={userId2}&type={type}`

Checks if a specific relationship exists.

**Query Parameters:**

- `fromUserId`: Source user ID
- `toUserId`: Target user ID
- `type`: Relationship type

**Response:**

```json
{
  "exists": true
}
```

---

## Usage Examples

### Favoriting a User

```http
POST /api/v1/relationships/create
Content-Type: application/json
X-User-ID: user-uuid-1
X-Timestamp: 1702472400000
X-Signature: <signature>

{
  "fromUserId": "user-uuid-1",
  "toUserId": "user-uuid-2",
  "relationshipType": "favorite"
}
```

### Sending a Friend Request

```http
POST /api/v1/relationships/create
{
  "fromUserId": "user-uuid-1",
  "toUserId": "user-uuid-2",
  "relationshipType": "friend_request_sent"
}
```

### Accepting a Friend Request

```http
POST /api/v1/relationships/friend-request/accept
{
  "userId": "user-uuid-2",
  "friendUserId": "user-uuid-1"
}
```

After this, both users will have a mutual `friend` relationship.

### Blocking a User

```http
POST /api/v1/relationships/create
{
  "fromUserId": "user-uuid-1",
  "toUserId": "user-uuid-2",
  "relationshipType": "blocked"
}
```

After blocking, user-uuid-2 cannot send messages, friend requests, or interact with user-uuid-1.

### Unfavoriting a User

```http
POST /api/v1/relationships/remove
{
  "fromUserId": "user-uuid-1",
  "toUserId": "user-uuid-2",
  "relationshipType": "favorite"
}
```

---

## Database Schema

The relationships are stored in the `user_relationships` table:

```sql
CREATE TABLE user_relationships (
    user_id_from VARCHAR(255) NOT NULL,
    user_id_to VARCHAR(255) NOT NULL,
    relationship_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id_from, user_id_to, relationship_type)
);
```

**Important Notes:**

- Composite primary key ensures a user can only have one relationship of each type with another user
- One-way relationships (like `favorite`, `blocked`) only exist in one direction
- Two-way relationships (like `friend`, `chatted`) exist as separate entries for both users
- Friend requests start as `friend_request_sent` and convert to mutual `friend` when accepted

---

## Integration with Other Features

### Chat System

When users exchange messages, the chat system should automatically create `chatted` relationships:

```kotlin
// After first message exchange
relationshipsDao.createRelationship(senderId, recipientId, RelationshipType.CHATTED)
relationshipsDao.createRelationship(recipientId, senderId, RelationshipType.CHATTED)
```

### Trade System

After a successful trade, create mutual `traded` relationships:

```kotlin
relationshipsDao.createRelationship(user1Id, user2Id, RelationshipType.TRADED)
relationshipsDao.createRelationship(user2Id, user1Id, RelationshipType.TRADED)
```

### Profile Discovery

Before showing a user in search results, check if they're blocked or hidden:

```kotlin
val isBlocked = relationshipsDao.isBlocked(currentUserId, targetUserId) ||
                relationshipsDao.isBlocked(targetUserId, currentUserId)
val isHidden = relationshipsDao.relationshipExists(currentUserId, targetUserId, RelationshipType.HIDDEN)

if (!isBlocked && !isHidden) {
    // Show user in results
}
```

---

## Error Handling

All endpoints return appropriate HTTP status codes:

- `200 OK`: Successful operation
- `400 Bad Request`: Invalid request (malformed data, invalid relationship type, etc.)
- `403 Forbidden`: Unauthorized (trying to access another user's relationships)
- `404 Not Found`: Relationship not found (when trying to remove non-existent relationship)
- `500 Internal Server Error`: Server error

Error responses include a JSON object with an `error` field:

```json
{
  "error": "Description of the error"
}
```
