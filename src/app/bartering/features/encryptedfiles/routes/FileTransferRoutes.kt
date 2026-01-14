package app.bartering.features.encryptedfiles.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.bartering.features.encryptedfiles.dao.EncryptedFileDaoImpl
import app.bartering.features.chat.manager.ConnectionManager
import app.bartering.features.encryptedfiles.model.*
import app.bartering.features.chat.model.FileNotificationMessage
import app.bartering.features.chat.model.SocketMessage
import io.ktor.websocket.Frame
import kotlinx.coroutines.isActive
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * REST endpoints for encrypted file upload/download
 * Used in conjunction with WebSocket for file notifications
 *
 * Flow:
 * 1. Client encrypts file with recipient's public key
 * 2. Client uploads encrypted file via POST /chat/files/upload
 * 3. Server stores encrypted file and returns fileId
 * 4. Server notifies recipient via WebSocket (if online)
 * 5. Recipient downloads file via GET /chat/files/download/{fileId}
 * 6. Recipient decrypts file with their private key
 */
fun Application.fileTransferRoutes(connectionManager: ConnectionManager) {
    val fileDao = EncryptedFileDaoImpl()
    val socketSerializer: KSerializer<SocketMessage> = SocketMessage.serializer()

    routing {
        route("/chat/files") {

            /**
             * Upload an encrypted file
             * Expects multipart form data with:
             * - file: encrypted file content
             * - recipientId: recipient user ID
             * - filename: original filename
             * - mimeType: file MIME type
             * - ttlHours: time-to-live in hours (default 24)
             *
             * Returns: fileId for download reference
             */
            post("/upload") {
                try {
                    // Get authenticated user ID from session
                    // For now, we'll require senderId in form data
                    var senderId = ""
                    var recipientId = ""
                    var filename = ""
                    var mimeType = ""
                    var ttlHours = 24L
                    var fileBytes: ByteArray? = null

                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "senderId" -> senderId = part.value
                                    "recipientId" -> recipientId = part.value
                                    "filename" -> filename = part.value
                                    "mimeType" -> mimeType = part.value
                                    "ttlHours" -> ttlHours = part.value.toLongOrNull() ?: 24L
                                }
                            }

                            is PartData.FileItem -> {
                                fileBytes = part.provider().readRemaining().readByteArray()
                            }

                            else -> {}
                        }
                        part.dispose()
                    }

                    // Validate required fields
                    if (senderId.isBlank() || recipientId.isBlank() || filename.isBlank() || fileBytes == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            FileErrorResponse(error = "Missing required fields: senderId, recipientId, filename, file")
                        )
                        return@post
                    }

                    // Enforce file size limit (e.g., 50MB for encrypted files)
                    val maxFileSize = 50 * 1024 * 1024L // 50MB
                    if (fileBytes.size > maxFileSize) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            FileErrorResponse(error = "File size exceeds maximum limit of 50MB")
                        )
                        return@post
                    }

                    // Create file metadata
                    val fileId = UUID.randomUUID().toString()
                    val expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS).toEpochMilli()

                    val fileDto = EncryptedFileDto(
                        id = fileId,
                        senderId = senderId,
                        recipientId = recipientId,
                        filename = filename,
                        mimeType = mimeType,
                        fileSize = fileBytes.size.toLong(),
                        expiresAt = expiresAt
                    )

                    // Store encrypted file
                    val stored = withContext(Dispatchers.IO) {
                        fileDao.storeEncryptedFile(fileDto, fileBytes)
                    }

                    if (!stored) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            FileErrorResponse(error = "Failed to store encrypted file")
                        )
                        return@post
                    }

                    println("Encrypted file uploaded: $fileId (${fileBytes.size} bytes) from $senderId to $recipientId")

                    // Notify recipient via WebSocket if online
                    val recipientConnection = connectionManager.getConnection(recipientId)
                    if (recipientConnection != null && recipientConnection.session.isActive) {
                        try {
                            val notification = FileNotificationMessage(
                                fileId = fileId,
                                senderId = senderId,
                                filename = filename,
                                mimeType = mimeType,
                                fileSize = fileBytes.size.toLong(),
                                expiresAt = expiresAt,
                                timestamp = System.currentTimeMillis()
                            )
                            recipientConnection.session.send(
                                Frame.Text(Json.encodeToString(socketSerializer, notification))
                            )
                            println("File notification sent to online recipient: $recipientId")
                        } catch (e: Exception) {
                            println("Failed to send file notification to $recipientId: ${e.message}")
                        }
                    } else {
                        println("Recipient $recipientId is offline. File will be available when they come online.")
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        FileUploadResponse(
                            success = true,
                            fileId = fileId,
                            expiresAt = expiresAt,
                            message = "File uploaded successfully"
                        )
                    )

                } catch (e: Exception) {
                    println("Error uploading file: ${e.message}")
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        FileErrorResponse(error = "Upload failed: ${e.message}")
                    )
                }
            }

            /**
             * Download an encrypted file
             * GET /chat/files/download/{fileId}?userId={userId}
             *
             * Returns: encrypted file bytes
             */
            get("/download/{fileId}") {
                try {
                    val fileId = call.parameters["fileId"]
                    val userId = call.request.queryParameters["userId"]

                    if (fileId.isNullOrBlank() || userId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            FileErrorResponse(error = "Missing fileId or userId")
                        )
                        return@get
                    }

                    // Get file metadata and content
                    val result = withContext(Dispatchers.IO) {
                        fileDao.getEncryptedFile(fileId)
                    }

                    if (result == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            FileErrorResponse(error = "File not found or expired")
                        )
                        return@get
                    }

                    val (metadata, encryptedContent) = result

                    // Verify user is the intended recipient
                    if (metadata.recipientId != userId) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            FileErrorResponse(error = "Access denied")
                        )
                        return@get
                    }

                    // Check if file has expired
                    if (metadata.expiresAt < System.currentTimeMillis()) {
                        call.respond(
                            HttpStatusCode.Gone,
                            FileErrorResponse(error = "File has expired")
                        )
                        return@get
                    }

                    // Mark as downloaded (will be deleted in next cleanup)
                    withContext(Dispatchers.IO) {
                        fileDao.markAsDownloaded(fileId)
                    }

                    println("File downloaded: $fileId by $userId")

                    // Send encrypted file
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName,
                            metadata.filename
                        ).toString()
                    )
                    call.respondBytes(
                        encryptedContent,
                        ContentType.Application.OctetStream
                    )

                } catch (e: Exception) {
                    println("Error downloading file: ${e.message}")
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        FileErrorResponse(error = "Download failed: ${e.message}")
                    )
                }
            }

            /**
             * Get list of pending files for a user
             * GET /chat/files/pending?userId={userId}
             */
            get("/pending") {
                try {
                    val userId = call.request.queryParameters["userId"]

                    if (userId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            FileErrorResponse(error = "Missing userId")
                        )
                        return@get
                    }

                    val pendingFiles = withContext(Dispatchers.IO) {
                        fileDao.getPendingFiles(userId)
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        PendingFilesResponse(
                            files = pendingFiles.map { file ->
                                FileMetadataDto(
                                    id = file.id,
                                    senderId = file.senderId,
                                    filename = file.filename,
                                    mimeType = file.mimeType,
                                    fileSize = file.fileSize,
                                    expiresAt = file.expiresAt
                                )
                            }
                        )
                    )

                } catch (e: Exception) {
                    println("Error fetching pending files: ${e.message}")
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        FileErrorResponse(error = "Failed to fetch pending files: ${e.message}")
                    )
                }
            }
        }
    }
}
