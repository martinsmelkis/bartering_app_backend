# E2EE File Sharing Implementation Guide

## Overview

This implementation provides **end-to-end encrypted (E2EE) file sharing** between users using a *
*hybrid approach** that combines temporary encrypted blob storage with WebSocket notifications. The
server never has access to unencrypted file content.

## Key Features

- ✅ **End-to-End Encryption**: Files are encrypted client-side before upload
- ✅ **Zero Server Knowledge**: Server only stores encrypted blobs
- ✅ **Temporary Storage**: Files have configurable TTL (default 24 hours)
- ✅ **Offline Support**: Files stored until recipient comes online
- ✅ **Real-time Notifications**: WebSocket alerts for instant file availability
- ✅ **Automatic Cleanup**: Background task removes expired/downloaded files
- ✅ **File Size Limit**: 50MB max per file (configurable)

## Architecture

```
┌─────────────┐                          ┌────────────┐
│   Sender    │                          │  Recipient │
│   Client    │                          │   Client   │
└──────┬──────┘                          └─────┬──────┘
       │                                       │
       │ 1. Encrypt file                       │
       │    with recipient's                   │
       │    public key                         │
       │                                       │
       │ 2. POST /chat/files/upload            │
       ├───────────────────┐                   │
       │                   │                   │
       │              ┌────▼────┐              │
       │              │         │              │
       │              │ Server  │              │
       │              │         │              │
       │              └────┬────┘              │
       │                   │                   │
       │ 3. Returns fileId │                   │
       │◄──────────────────┤                   │
       │                   │                   │
       │                   │ 4. WebSocket      │
       │                   │    notification   │
       │                   ├──────────────────►│
       │                   │                   │
       │                   │ 5. GET /chat/     │
       │                   │    files/download │
       │                   │◄──────────────────┤
       │                   │                   │
       │                   │ 6. Encrypted file │
       │                   ├──────────────────►│
       │                   │                   │
       │                   │                   │ 7. Decrypt with
       │                   │                   │    private key
       │                   │                   │
```

## Components

### 1. Database Layer

**Table: `encrypted_files`**

- Stores encrypted file content as BYTEA (binary)
- Includes metadata: sender, recipient, filename, size, TTL
- Indexed for efficient queries

### 2. Data Models

**EncryptedFileDto**: File metadata without content
**FileMetadataDto**: Lightweight metadata for listings

### 3. DAO Layer

**EncryptedFileDao / EncryptedFileDaoImpl**

- `storeEncryptedFile()`: Save encrypted file
- `getEncryptedFile()`: Retrieve file + metadata
- `markAsDownloaded()`: Flag for cleanup
- `deleteExpiredFiles()`: Cleanup operation
- `getPendingFiles()`: Get files waiting for user

### 4. REST Endpoints

#### Upload Endpoint

```http
POST /chat/files/upload
Content-Type: multipart/form-data

Parameters:
- senderId: string (sender user ID)
- recipientId: string (recipient user ID)
- filename: string (original filename)
- mimeType: string (MIME type)
- ttlHours: number (optional, default 24)
- file: binary (encrypted file content)

Response:
{
  "success": true,
  "fileId": "uuid",
  "expiresAt": 1234567890,
  "message": "File uploaded successfully"
}
```

#### Download Endpoint

```http
GET /chat/files/download/{fileId}?userId={userId}

Response:
- Binary encrypted file content
- Content-Disposition header with filename
```

#### Pending Files Endpoint

```http
GET /chat/files/pending?userId={userId}

Response:
{
  "files": [
    {
      "fileId": "uuid",
      "senderId": "user123",
      "filename": "photo.jpg",
      "mimeType": "image/jpeg",
      "fileSize": 1024000,
      "expiresAt": 1234567890
    }
  ]
}
```

### 5. WebSocket Integration

**New Message Types:**

```kotlin
@Serializable
data class FileNotificationMessage(
    val type: String = "file_notification",
    val fileId: String,
    val senderId: String,
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val expiresAt: Long,
    val timestamp: Long
) : SocketMessage()
```

**Flow:**

1. User connects via WebSocket
2. Server sends pending file notifications
3. When new file uploaded, recipient notified in real-time (if online)

### 6. Background Cleanup

**FileCleanupTask**

- Runs every hour (configurable)
- Deletes files that are:
    - Expired (past TTL)
    - Already downloaded
- Automatic, no manual intervention needed

## Client Implementation Guide

### Android/Kotlin Client Example

```kotlin
// 1. Encrypt file with recipient's public key
fun encryptFileForRecipient(
    fileBytes: ByteArray,
    recipientPublicKey: String
): ByteArray {
    // Use ECIES or similar asymmetric encryption
    val publicKey = convertToECPublicKey(recipientPublicKey)
    val cipher = Cipher.getInstance("ECIES")
    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
    return cipher.doFinal(fileBytes)
}

// 2. Upload encrypted file
suspend fun uploadEncryptedFile(
    senderId: String,
    recipientId: String,
    filename: String,
    mimeType: String,
    encryptedBytes: ByteArray
): String {
    val client = HttpClient()
    val response = client.submitFormWithBinaryData(
        url = "https://your-server.com/chat/files/upload",
        formData = formData {
            append("senderId", senderId)
            append("recipientId", recipientId)
            append("filename", filename)
            append("mimeType", mimeType)
            append("ttlHours", "24")
            append("file", encryptedBytes, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream")
                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
            })
        }
    )
    val result = response.body<JsonObject>()
    return result["fileId"]?.jsonPrimitive?.content ?: throw Exception("Upload failed")
}

// 3. Listen for file notifications via WebSocket
fun handleWebSocketMessage(message: String) {
    val socketMessage = Json.decodeFromString<SocketMessage>(message)
    
    when (socketMessage) {
        is FileNotificationMessage -> {
            // Show notification to user
            showFileAvailableNotification(
                from = socketMessage.senderId,
                filename = socketMessage.filename,
                fileId = socketMessage.fileId
            )
        }
        // ... other message types
    }
}

// 4. Download and decrypt file
suspend fun downloadAndDecryptFile(
    fileId: String,
    userId: String,
    privateKey: PrivateKey
): ByteArray {
    val client = HttpClient()
    val encryptedBytes = client.get("https://your-server.com/chat/files/download/$fileId?userId=$userId")
        .body<ByteArray>()
    
    // Decrypt with user's private key
    val cipher = Cipher.getInstance("ECIES")
    cipher.init(Cipher.DECRYPT_MODE, privateKey)
    return cipher.doFinal(encryptedBytes)
}
```

### Flutter/Dart Client Example

```dart
import 'package:http/http.dart' as http;
import 'package:pointycastle/pointycastle.dart';

// 1. Encrypt file
Future<Uint8List> encryptFileForRecipient(
    Uint8List fileBytes,
    String recipientPublicKey) async {
  // Use EC public key for encryption
  final publicKey = parseECPublicKey(recipientPublicKey);
  final cipher = ECIESCipher()..init(true, PublicKeyParameter(publicKey));
  return cipher.process(fileBytes);
}

// 2. Upload encrypted file
Future<String> uploadEncryptedFile({
  required String senderId,
  required String recipientId,
  required String filename,
  required String mimeType,
  required Uint8List encryptedBytes,
}) async {
  var request = http.MultipartRequest(
    'POST',
    Uri.parse('https://your-server.com/chat/files/upload'),
  );
  
  request.fields['senderId'] = senderId;
  request.fields['recipientId'] = recipientId;
  request.fields['filename'] = filename;
  request.fields['mimeType'] = mimeType;
  request.fields['ttlHours'] = '24';
  
  request.files.add(http.MultipartFile.fromBytes(
    'file',
    encryptedBytes,
    filename: filename,
  ));
  
  var response = await request.send();
  var responseData = await response.stream.bytesToString();
  var json = jsonDecode(responseData);
  
  return json['fileId'];
}

// 3. Handle WebSocket notifications
void handleWebSocketMessage(String message) {
  final data = jsonDecode(message);
  
  if (data['type'] == 'file_notification') {
    // Show notification
    showFileAvailableNotification(
      from: data['senderId'],
      filename: data['filename'],
      fileId: data['fileId'],
    );
  }
}

// 4. Download and decrypt
Future<Uint8List> downloadAndDecryptFile(
    String fileId,
    String userId,
    ECPrivateKey privateKey) async {
  final response = await http.get(
    Uri.parse('https://your-server.com/chat/files/download/$fileId?userId=$userId'),
  );
  
  final cipher = ECIESCipher()..init(false, PrivateKeyParameter(privateKey));
  return cipher.process(response.bodyBytes);
}
```

## Security Considerations

### ✅ What's Secure

1. **E2EE**: Server never sees unencrypted content
2. **Public Key Verification**: Files encrypted with verified public keys
3. **Access Control**: Only intended recipient can download
4. **Automatic Expiration**: Files auto-delete after TTL
5. **Download Tracking**: Files marked for deletion after download

### ⚠️ Important Notes

1. **Client Responsibility**: Encryption/decryption happens client-side
2. **Metadata Visible**: Server sees sender, recipient, filename, size
3. **Filename Privacy**: Consider encrypting filenames client-side
4. **File Size Limit**: 50MB default (adjust based on needs)
5. **TTL Selection**: Balance between availability and storage costs

### 🔒 Best Practices

1. **Use Strong Encryption**: ECIES with P-256 or higher
2. **Verify Keys**: Always verify recipient public key authenticity
3. **Secure Key Storage**: Never expose private keys
4. **Transport Security**: Always use HTTPS
5. **File Type Validation**: Validate on client before encryption
6. **Progress Indicators**: Show upload/download progress to users

## Configuration

### File Size Limit

Adjust in `FileTransferRoutes.kt`:

```kotlin
val maxFileSize = 50 * 1024 * 1024L // 50MB
```

### Default TTL

Adjust in upload endpoint or client:

```kotlin
var ttlHours = 24L // Default 24 hours
```

### Cleanup Frequency

Adjust in `ChatRoutes.kt`:

```kotlin
val fileCleanupTask = FileCleanupTask(
    encryptedFileDao, 
    intervalHours = 1  // Run every hour
)
```

## Testing

### Manual Testing Steps

1. **Upload Test**
   ```bash
   curl -X POST http://localhost:8081/chat/files/upload \
     -F "senderId=user1" \
     -F "recipientId=user2" \
     -F "filename=test.jpg" \
     -F "mimeType=image/jpeg" \
     -F "ttlHours=1" \
     -F "file=@encrypted_test.bin"
   ```

2. **Download Test**
   ```bash
   curl -X GET "http://localhost:8081/chat/files/download/{fileId}?userId=user2" \
     --output downloaded_encrypted.bin
   ```

3. **Pending Files Test**
   ```bash
   curl -X GET "http://localhost:8081/chat/files/pending?userId=user2"
   ```

### Integration Tests

```kotlin
@Test
fun testFileUploadAndDownload() = testApplication {
    // 1. Upload encrypted file
    val uploadResponse = client.submitFormWithBinaryData(
        url = "/chat/files/upload",
        formData = formData {
            append("senderId", "user1")
            append("recipientId", "user2")
            append("filename", "test.jpg")
            append("mimeType", "image/jpeg")
            append("file", testEncryptedBytes)
        }
    )
    assertEquals(HttpStatusCode.Created, uploadResponse.status)
    val fileId = uploadResponse.body<JsonObject>()["fileId"]!!.jsonPrimitive.content
    
    // 2. Download file
    val downloadResponse = client.get("/chat/files/download/$fileId?userId=user2")
    assertEquals(HttpStatusCode.OK, downloadResponse.status)
    assertArrayEquals(testEncryptedBytes, downloadResponse.body<ByteArray>())
}
```

## Troubleshooting

### File Upload Fails

- Check file size doesn't exceed 50MB
- Verify all required fields are present
- Check server logs for errors

### File Not Received

- Verify WebSocket connection is active
- Check recipient ID is correct
- Use `/chat/files/pending` endpoint to check manually

### File Expired

- Files auto-delete after TTL expires
- Default TTL is 24 hours
- Increase TTL if needed for slow connections

### Cleanup Not Working

- Check FileCleanupTask is started in ChatRoutes
- Verify database connection is healthy
- Check server logs for cleanup errors

## Future Enhancements

1. **Chunked Upload/Download**: For very large files
2. **Resume Support**: Handle interrupted transfers
3. **Multiple Recipients**: Encrypt once for multiple recipients
4. **File System Storage**: Move from DB to file system/S3
5. **Compression**: Compress before encryption
6. **Thumbnail Generation**: Generate encrypted thumbnails for images
7. **Progress Tracking**: Server-side upload/download progress
8. **Quota Management**: Per-user storage limits
