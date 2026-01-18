# User Deletion API Documentation

## Overview

The User Deletion API provides a secure endpoint for users to permanently delete their accounts and all associated data from the system. This endpoint implements GDPR-compliant "right to be forgotten" functionality.

## Endpoint

```
DELETE /api/v1/authentication/user/{userId}
```

## Authentication

This endpoint requires **signature verification** to ensure that only the account owner can delete their own account.

### Required Headers

- `X-User-ID`: The user ID of the authenticated user
- `X-Timestamp`: Current timestamp in milliseconds (must be within 5 minutes)
- `X-Signature`: Base64-encoded ECDSA signature of the challenge string

### Challenge String Format

```
{timestamp}.{requestBody}
```

The signature must be generated using the user's private key (ECDSA with SHA-256) over the challenge string.

## Request

### Path Parameters

- `userId` (required): The ID of the user to delete

### Request Body

```json
{
  "userId": "user-id-to-delete",
  "confirmation": true
}
```

**Fields:**
- `userId` (required): Must match the path parameter
- `confirmation` (optional): Boolean confirmation flag (defaults to true)

### Example Request

```bash
curl -X DELETE "http://localhost:8081/api/v1/authentication/user/user123" \
  -H "X-User-ID: user123" \
  -H "X-Timestamp: 1703347200000" \
  -H "X-Signature: Base64EncodedSignature..." \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user123",
    "confirmation": true
  }'
```

## Response

### Success Response (200 OK)

```json
{
  "success": true,
  "message": "User account and all associated data have been permanently deleted"
}
```

### Error Responses

#### 400 Bad Request - Missing User ID

```json
{
  "success": false,
  "message": "User ID is required"
}
```

#### 400 Bad Request - Mismatched User IDs

```json
{
  "success": false,
  "message": "User ID in request body does not match path parameter"
}
```

#### 401 Unauthorized - Invalid Signature

```json
{
  "success": false,
  "message": "Invalid signature"
}
```

#### 403 Forbidden - Unauthorized Deletion

```json
{
  "success": false,
  "message": "You are not authorized to delete this user account"
}
```

#### 404 Not Found - User Not Found

```json
{
  "success": false,
  "message": "User not found or already deleted"
}
```

#### 500 Internal Server Error

```json
{
  "success": false,
  "message": "An error occurred while deleting the user: {error details}"
}
```

## Data Deletion Scope

When a user is deleted, the following data is **permanently removed** from the system:

### Directly Deleted (Manual)
1. **Activity Cache**: User removed from in-memory activity cache (prevents FK violations)
2. **Offline Messages**: All messages where the user is sender or recipient
3. **Encrypted Files**: All files where the user is sender or recipient

### Cascade Deleted (Database FK Constraints)
4. **User Profile**: The user's profile information (name, location, etc.)
5. **User Attributes**: All skill/interest attributes associated with the user
6. **User Relationships**: All connections and blocks (both directions)
7. **User Postings**: All marketplace offers and interests created by the user
8. **Posting Attributes**: All attribute links for the user's postings
9. **User Presence**: User activity tracking and online/offline status

### Summary of Deleted Records

| Data Type | Table | Deletion Method |
|-----------|-------|-----------------|
| User Account | `user_registration_data` | Primary deletion |
| User Profile | `user_profiles` | CASCADE (FK) |
| User Attributes | `user_attributes` | CASCADE (FK) |
| Relationships | `user_relationships` | CASCADE (FK) |
| User Postings | `user_postings` | CASCADE (FK) |
| Posting Attributes | `posting_attributes_link` | CASCADE (FK) |
| User Presence | `user_presence` | CASCADE (FK) |
| Activity Cache | In-memory cache | Manual (removed first) |
| Offline Messages | `offline_messages` | Manual (sender/recipient) |
| Encrypted Files | `encrypted_files` | Manual (sender/recipient) |

## Security Features

### 1. Signature Verification
- Uses ECDSA (Elliptic Curve Digital Signature Algorithm) with SHA-256
- Prevents unauthorized account deletion
- Timestamp validation prevents replay attacks

### 2. Authorization Check
- The authenticated user ID must match the user being deleted
- Users cannot delete other users' accounts

### 3. Timestamp Validation
- Requests must be made within 5 minutes of the timestamp
- Prevents replay attacks

## GDPR Compliance

This endpoint implements GDPR Article 17 - "Right to Erasure" (Right to be Forgotten):

- ✅ **Complete Data Removal**: All user data is permanently deleted
- ✅ **No Retention**: No soft-delete or data retention after deletion
- ✅ **Cascade Deletion**: Related data across all tables is removed
- ✅ **User-Initiated**: Only the user can delete their own account
- ✅ **Irreversible**: Deletion is permanent and cannot be undone

## Implementation Details

### DAO Layer

The deletion is implemented in `AuthenticationDaoImpl.deleteUserAndAllData()`:

```kotlin
override suspend fun deleteUserAndAllData(userId: String): Boolean {
    return dbQuery {
        try {
            // Step 1: Remove user from activity cache
            // This prevents foreign key constraint violations during background sync
            UserActivityCache.removeUser(userId)
            
            // Step 2: Delete offline messages
            OfflineMessagesTable.deleteWhere { 
                (senderId eq userId) or (recipientId eq userId)
            }
            
            // Step 3: Delete encrypted files
            EncryptedFilesTable.deleteWhere { 
                (senderId eq userId) or (recipientId eq userId)
            }
            
            // Step 4: Delete user (cascades to related tables including user_presence)
            UserRegistrationDataTable.deleteWhere { 
                id eq userId 
            }
            
            deletedCount > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
```

### Database Transaction

All deletions are executed within a single database transaction to ensure:
- **Atomicity**: All deletions succeed or all fail
- **Consistency**: Database remains in a consistent state
- **Isolation**: No concurrent operations interfere
- **Durability**: Once committed, deletions are permanent

## Testing

### Manual Testing

1. Create a test user account
2. Generate signature using the user's private key
3. Call the delete endpoint with proper headers
4. Verify all data is deleted from the database

### Integration Test Example

```kotlin
@Test
fun `test user deletion removes all associated data`() = testApplication {
    // Arrange
    val testUserId = "test-user-123"
    val timestamp = System.currentTimeMillis()
    val requestBody = """{"userId": "$testUserId", "confirmation": true}"""
    val signature = generateSignature(timestamp, requestBody, userPrivateKey)
    
    // Act
    client.delete("/api/v1/authentication/user/$testUserId") {
        headers {
            append("X-User-ID", testUserId)
            append("X-Timestamp", timestamp.toString())
            append("X-Signature", signature)
        }
        setBody(requestBody)
    }.apply {
        // Assert
        assertEquals(HttpStatusCode.OK, status)
        val response = bodyAsText()
        assertTrue(response.contains("\"success\":true"))
    }
    
    // Verify data is deleted
    val userExists = authDao.getUserInfoById(testUserId)
    assertNull(userExists)
}
```

## Best Practices

### For Client Applications

1. **Warn Users**: Display a confirmation dialog before deletion
2. **Explain Consequences**: Clearly state that deletion is permanent
3. **Backup Option**: Offer data export before deletion
4. **Signature Generation**: Ensure proper signature generation using user's private key
5. **Error Handling**: Handle all possible error responses gracefully

### Example Client Flow

```
1. User clicks "Delete Account"
2. Show confirmation dialog with warning
3. User confirms deletion
4. Generate timestamp and signature
5. Send DELETE request with proper headers
6. On success: Log user out and clear local data
7. On error: Display appropriate error message
```

## Monitoring & Logging

### Logged Events

- User deletion requests (successful and failed)
- Authorization failures
- Signature verification failures
- Database errors during deletion

### Recommended Monitoring

- Track deletion rate over time
- Alert on unusual deletion spikes
- Monitor deletion errors and failures
- Audit log retention for compliance

## Future Enhancements

Potential improvements for this endpoint:

- [ ] Email confirmation before deletion
- [ ] Grace period for account recovery (soft delete first)
- [ ] Data export option before deletion
- [ ] Admin override capability for legal compliance
- [ ] Deletion webhook notifications for integrated services
- [ ] Anonymization option instead of full deletion
- [ ] Batch deletion for admin purposes

## Support & Troubleshooting

### Common Issues

**Problem**: "Invalid signature" error
- **Solution**: Verify signature generation algorithm matches server expectations
- **Check**: Timestamp is within 5-minute window
- **Verify**: Challenge string format is exactly "{timestamp}.{requestBody}"

**Problem**: "User not found or already deleted"
- **Solution**: User may have already been deleted or never existed
- **Action**: Check database for user existence

**Problem**: "You are not authorized to delete this user account"
- **Solution**: The X-User-ID header doesn't match the userId in the path
- **Action**: Ensure user is deleting their own account

## Related Documentation

- [Authentication API](./AUTHENTICATION_API.md)
- [Signature Verification](../src/org/barter/utils/SignatureVerification.kt)
- [GDPR Compliance Guide](./GDPR_COMPLIANCE.md)
- [Data Privacy Policy](./DATA_PRIVACY.md)

## API Changelog

### Version 1.0 (Current)
- Initial implementation
- Signature-based authentication
- Cascade deletion for all related data
- GDPR compliance

---

**Last Updated**: December 23, 2025  
**API Version**: 1.0  
**Stability**: Stable
