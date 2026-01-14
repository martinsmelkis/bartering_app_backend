# User Block and Report API Documentation

This document describes the user blocking and reporting functionality available to clients.

## Table of Contents
1. [User Blocking](#user-blocking)
2. [User Reporting](#user-reporting)
3. [Data Models](#data-models)
4. [Integration Guide](#integration-guide)

---

## User Blocking

### Block a User

Block a user to prevent all interactions.

**Endpoint:** `POST /api/v1/users/block`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Request Body:**
```json
{
  "fromUserId": "user-uuid-123",
  "toUserId": "user-uuid-456",
  "relationshipType": "blocked"
}
```

**Response (200 OK):**
```json
{
  "success": true
}
```

**Errors:**
- `400` - Cannot block yourself
- `403` - You can only block users for yourself
- `500` - Failed to block user

**Side Effects:**
- Removes any friend relationship (bidirectional)
- Removes any pending friend requests (bidirectional)
- Creates a BLOCKED relationship entry
- Blocked user cannot:
  - See your profile in search results
  - Send you messages
  - Send you friend requests
  - Comment on your postings

---

### Unblock a User

Remove a block on a user.

**Endpoint:** `POST /api/v1/users/unblock`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Request Body:**
```json
{
  "fromUserId": "user-uuid-123",
  "toUserId": "user-uuid-456",
  "relationshipType": "blocked"
}
```

**Response (200 OK):**
```json
{
  "success": true
}
```

**Errors:**
- `403` - You can only unblock users for yourself
- `404` - Block relationship not found

---

### Check if User is Blocked

Check if you have blocked a specific user.

**Endpoint:** `GET /api/v1/users/isBlocked?fromUserId=<userId>&toUserId=<otherUserId>`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Response (200 OK):**
```json
{
  "isBlocked": true
}
```

**Errors:**
- `400` - Missing required parameters
- `403` - You can only check your own block status

---

### Get Blocked Users List

Get all users you have blocked with their profile information.

**Endpoint:** `GET /api/v1/users/blocked/<userId>`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Response (200 OK):**
```json
[
  {
    "userId": "user-uuid-456",
    "name": "John Doe",
    "latitude": 37.7749,
    "longitude": -122.4194,
    "attributes": [...],
    "profileKeywordDataMap": {...},
    "activePostingIds": [...]
  }
]
```

**Errors:**
- `400` - Missing userId parameter
- `403` - You can only view your own blocked users

---

### Get Users Who Blocked You

Get list of user IDs who have blocked you.

**Endpoint:** `GET /api/v1/users/blockedBy/<userId>`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Response (200 OK):**
```json
{
  "blockedByUsers": ["user-uuid-789", "user-uuid-012"]
}
```

**Note:** This returns only user IDs for privacy reasons.

---

## User Reporting

### Report a User

Report a user for inappropriate behavior.

**Endpoint:** `POST /api/v1/reports/create`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Request Body:**
```json
{
  "reporterUserId": "user-uuid-123",
  "reportedUserId": "user-uuid-456",
  "reportReason": "harassment",
  "description": "User sent threatening messages",
  "contextType": "chat",
  "contextId": "chat-message-id-789"
}
```

**Report Reasons:**
- `spam` - Unsolicited promotional content
- `harassment` - Bullying or hostile behavior
- `inappropriate_content` - Offensive or explicit content
- `scam` - Fraudulent or deceptive behavior
- `fake_profile` - Impersonation or fake identity
- `impersonation` - Pretending to be someone else
- `threatening_behavior` - Threats of violence or harm
- `other` - Other reasons (explain in description)

**Context Types:**
- `profile` - Report user's profile
- `posting` - Report a specific posting
- `chat` - Report chat messages
- `review` - Report a review
- `general` - General report about user

**Response (201 Created):**
```json
{
  "success": true,
  "reportId": "report-uuid-789"
}
```

**Errors:**
- `400` - Invalid report reason or context type
- `400` - Cannot report yourself
- `403` - You can only file reports for yourself
- `409` - You have already reported this user

**Side Effects:**
- Creates a report entry for moderation review
- Automatically creates a REPORTED relationship
- May trigger automatic actions based on report count

---

### Get Your Filed Reports

Get all reports you have filed.

**Endpoint:** `GET /api/v1/reports/user/<userId>`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Response (200 OK):**
```json
[
  {
    "id": "report-uuid-789",
    "reporterUserId": "user-uuid-123",
    "reportedUserId": "user-uuid-456",
    "reportReason": "harassment",
    "description": "User sent threatening messages",
    "contextType": "chat",
    "contextId": "chat-message-id-789",
    "status": "pending",
    "reportedAt": "2026-01-11T10:30:00Z",
    "reviewedAt": null,
    "actionTaken": null
  }
]
```

**Errors:**
- `400` - Missing userId parameter
- `403` - You can only view your own reports

---

### Check if You Have Reported a User

Check if you have already reported a specific user.

**Endpoint:** `GET /api/v1/reports/check?reporterUserId=<userId>&reportedUserId=<otherUserId>`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Response (200 OK):**
```json
{
  "hasReported": true
}
```

**Errors:**
- `400` - Missing required parameters
- `403` - You can only check your own reports

---

### Get Report Statistics

Get statistics about reports against a user (public for transparency).

**Endpoint:** `GET /api/v1/reports/stats/<userId>`

**Request Headers:**
```
X-User-ID: <userId>
X-Signature: <signature>
X-Timestamp: <timestamp>
```

**Response (200 OK):**
```json
{
  "userId": "user-uuid-456",
  "totalReportsReceived": 5,
  "pendingReports": 2,
  "actionsTaken": 1,
  "lastReportedAt": "2026-01-10T15:20:00Z"
}
```

**Note:** This endpoint is public to allow users to see if someone has been reported multiple times, helping them make informed decisions about trades.

---

## Data Models

### Report Status Values

- `pending` - Report submitted, awaiting review
- `under_review` - Moderator is investigating
- `reviewed` - Review complete, decision made
- `dismissed` - Report was invalid or unfounded
- `action_taken` - Moderator took action on the report

### Report Action Values

- `warning` - User received a warning
- `temporary_ban` - User temporarily banned
- `permanent_ban` - User permanently banned
- `content_removed` - Specific content was removed
- `account_restricted` - Account features restricted
- `none` - No action taken (report dismissed)

---

## Integration Guide

### Basic Flow for Blocking a User

```kotlin
// 1. Block the user
val blockRequest = RelationshipRequest(
    fromUserId = currentUserId,
    toUserId = userToBlock,
    relationshipType = "blocked"
)

apiService.blockUser(blockRequest)

// 2. Update local state
viewModel.addToBlockedList(userToBlock)

// 3. Hide user from UI
userList.removeIf { it.userId == userToBlock }
```

### Basic Flow for Reporting a User

```kotlin
// 1. Show report dialog to user
val reportReason = showReportDialog() // Returns reason

// 2. Submit report
val reportRequest = UserReportRequest(
    reporterUserId = currentUserId,
    reportedUserId = userToReport,
    reportReason = reportReason,
    description = userEnteredDescription,
    contextType = "chat", // or null
    contextId = chatMessageId // or null
)

val response = apiService.reportUser(reportRequest)

// 3. Show confirmation
if (response.success) {
    showToast("User reported. Thank you for helping keep the community safe.")
    
    // Optionally offer to block the user
    showBlockUserDialog(userToReport)
}
```

### Checking Before Interactions

Before allowing a user to interact (send message, send friend request, etc.):

```kotlin
// Check if either user has blocked the other
val isBlocked = apiService.checkIsBlocked(currentUserId, otherUserId)
val isBlockedBy = apiService.checkIsBlocked(otherUserId, currentUserId)

if (isBlocked || isBlockedBy) {
    showError("Cannot interact with this user")
    return
}

// Check report statistics (optional - for user awareness)
val reportStats = apiService.getUserReportStats(otherUserId)
if (reportStats.totalReportsReceived > 3) {
    showWarning("This user has been reported ${reportStats.totalReportsReceived} times")
}
```

### UI Best Practices

1. **Block Button Placement:**
   - User profile screen: Action menu or "..." button
   - Chat screen: Header menu
   - After reporting: Offer to block

2. **Report Button Placement:**
   - User profile screen: Action menu
   - Chat screen: Header menu  
   - Posting detail: Report icon
   - Review detail: Report icon

3. **Confirmation Dialogs:**
   - Always confirm before blocking
   - Explain consequences (can't message, see posts, etc.)
   - Make unblocking easy to find

4. **Report Feedback:**
   - Show success message
   - Don't reveal moderation actions to reporter
   - Provide status updates if possible

---

## Security Considerations

1. **Rate Limiting:**
   - Maximum 10 reports per user per day
   - Maximum 3 blocks per minute

2. **Abuse Prevention:**
   - Cannot report the same user multiple times
   - Cannot block yourself
   - Reports are logged for abuse detection

3. **Privacy:**
   - Blocked users don't know they're blocked
   - Report submitter is kept confidential
   - Moderator actions are logged

4. **Validation:**
   - All user IDs validated against database
   - Report reasons must be valid enums
   - Context IDs checked for existence when possible

---

## Testing Endpoints

### Example cURL Commands

**Block a user:**
```bash
curl -X POST http://localhost:8080/api/v1/users/block \
  -H "Content-Type: application/json" \
  -H "X-User-ID: user-123" \
  -H "X-Signature: <signature>" \
  -H "X-Timestamp: <timestamp>" \
  -d '{
    "fromUserId": "user-123",
    "toUserId": "user-456",
    "relationshipType": "blocked"
  }'
```

**Report a user:**
```bash
curl -X POST http://localhost:8080/api/v1/reports/create \
  -H "Content-Type: application/json" \
  -H "X-User-ID: user-123" \
  -H "X-Signature: <signature>" \
  -H "X-Timestamp: <timestamp>" \
  -d '{
    "reporterUserId": "user-123",
    "reportedUserId": "user-456",
    "reportReason": "spam",
    "description": "Sending unsolicited advertisements"
  }'
```

---

## Future Enhancements

Potential future features:
- Temporary blocks (auto-expire after duration)
- Block categories (block from chat only, block from search, etc.)
- Appeal process for banned users
- Automatic actions based on report threshold
- Moderator dashboard for reviewing reports
- Report analytics and patterns
