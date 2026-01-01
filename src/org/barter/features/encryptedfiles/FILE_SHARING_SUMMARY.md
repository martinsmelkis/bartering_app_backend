# E2EE File Sharing - Quick Summary

## What Was Implemented

A complete **hybrid E2EE file sharing system** that allows users to send images and files to each
other without the server being able to decrypt or access the content.

## How It Works

1. **Client encrypts** file with recipient's public key
2. **Upload** encrypted file to server via REST API
3. **Server stores** encrypted blob with metadata and TTL
4. **Server notifies** recipient via WebSocket (if online)
5. **Recipient downloads** encrypted file
6. **Recipient decrypts** file with their private key
7. **Server auto-deletes** file after download or TTL expiration

## Key Features

- ✅ End-to-end encryption (server never sees plain text)
- ✅ Offline support (files stored until recipient comes online)
- ✅ Real-time notifications via WebSocket
- ✅ Automatic cleanup of expired/downloaded files
- ✅ 50MB file size limit
- ✅ Configurable TTL (default 24 hours)

## New Files Created

### Database

- `encrypted_files` table via `V2__Encrypted_files.sql` migration

### Models

- `EncryptedFileDto.kt` - File metadata
- `FileNotificationMessage` - WebSocket message type

### DAO

- `EncryptedFileDao.kt` - Interface
- `EncryptedFileDaoImpl.kt` - Implementation

### Routes

- `FileTransferRoutes.kt` - REST endpoints for upload/download

### Tasks

- `FileCleanupTask.kt` - Background cleanup

### Documentation

- `FILE_SHARING_GUIDE.md` - Complete implementation guide

## API Endpoints

```
POST   /chat/files/upload      - Upload encrypted file
GET    /chat/files/download/{fileId}  - Download encrypted file
GET    /chat/files/pending     - Get list of pending files
```

## Client Integration

Clients need to:

1. Encrypt files before upload
2. Handle `FileNotificationMessage` from WebSocket
3. Download and decrypt files when needed
4. Manage their private keys securely

See `FILE_SHARING_GUIDE.md` for detailed client examples.

## Security

- Server only stores encrypted blobs (BYTEA)
- Access control: only recipient can download
- Files auto-expire after TTL
- No server-side decryption capability
- Metadata (filename, size) is visible to server

## Configuration

All configurable values:

- File size limit: 50MB (in `FileTransferRoutes.kt`)
- Default TTL: 24 hours (configurable per upload)
- Cleanup frequency: 1 hour (in `ChatRoutes.kt`)

## Next Steps

To use this system:

1. Run database migration (automatic on startup)
2. Server will start file cleanup task automatically
3. Implement client-side encryption/decryption
4. Test with small files first
5. Monitor server logs for upload/download activity

## Testing

Manual test upload:

```bash
curl -X POST http://localhost:8081/chat/files/upload \
  -F "senderId=user1" \
  -F "recipientId=user2" \
  -F "filename=test.jpg" \
  -F "mimeType=image/jpeg" \
  -F "file=@encrypted_file.bin"
```

Manual test download:

```bash
curl "http://localhost:8081/chat/files/download/{fileId}?userId=user2" --output file.bin
```
