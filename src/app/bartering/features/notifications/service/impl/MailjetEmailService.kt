package app.bartering.features.notifications.service.impl

import app.bartering.features.notifications.model.EmailNotification
import app.bartering.features.notifications.model.NotificationResult
import app.bartering.features.notifications.service.EmailService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Mailjet Email Service Implementation
 *
 * European-based email service provider with GDPR compliance built-in.
 * Mailjet is owned by Sinch and operates primarily from EU data centers.
 *
 * Configuration:
 * - API Key: MAILJET_API_KEY (or MJ_APIKEY_PUBLIC)
 * - API Secret: MAILJET_API_SECRET (or MJ_APIKEY_PRIVATE)
 * - Default From: MAILJET_FROM_EMAIL (default: info@bartering.app)
 *
 * Features:
 * - EU data residency (GDPR compliant by default)
 * - Simple REST API (no complex SDK setup)
 * - Template management via API
 * - Real-time analytics
 * - Built-in contact management
 * - Automatic bounce/handling
 *
 * API Docs: https://dev.mailjet.com/email/reference/overview/
 *
 * GDPR Advantages over AWS SES:
 * - EU-based data processing by default
 * - No US data transfer required
 * - Built-in DPA (Data Processing Agreement)
 * - Fine-grained data retention controls
 * - Automatic suppression list management
 *
 * Environment Variables Required:
 * - MAILJET_API_KEY (Public API key)
 * - MAILJET_API_SECRET (Private API key)
 * - MAILJET_FROM_EMAIL (optional, default sender)
 */
class MailjetEmailService(
    private val apiKey: String = System.getenv("MAILJET_API_KEY")
        ?: System.getenv("MJ_APIKEY_PUBLIC")
        ?: throw IllegalArgumentException("MAILJET_API_KEY environment variable is required"),
    private val apiSecret: String = System.getenv("MAILJET_API_SECRET")
        ?: System.getenv("MJ_APIKEY_PRIVATE")
        ?: throw IllegalArgumentException("MAILJET_API_SECRET environment variable is required"),
    private val defaultFrom: String = System.getenv("MAILJET_FROM_EMAIL") ?: "info@bartering.app",
    private val sandboxMode: Boolean = System.getenv("MAILJET_SANDBOX")?.toBoolean() ?: false
) : EmailService {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    private val baseUrl = "https://api.mailjet.com/v3.1"

    /**
     * Send a single email via Mailjet API
     */
    override suspend fun sendEmail(email: EmailNotification): NotificationResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = buildSendRequest(email)

                val response: HttpResponse = httpClient.post("$baseUrl/send") {
                    basicAuth(apiKey, apiSecret)
                    setBody(request)
                }

                if (response.status.isSuccess()) {
                    val sendResponse: MailjetSendResponse = response.body()
                    val message = sendResponse.messages.firstOrNull()

                    if (message != null && message.status == "success") {
                        NotificationResult(
                            success = true,
                            messageId = message.to.firstOrNull()?.messageId?.toString(),
                            failedRecipients = emptyList(),
                            errorMessage = null,
                            metadata = mapOf(
                                "provider" to "mailjet",
                                "messageUuid" to (message.to.firstOrNull()?.messageUuid ?: ""),
                                "status" to message.status
                            )
                        )
                    } else {
                        val errors = message?.errors?.joinToString(", ") { it.errorMessage ?: "" } ?: "Unknown error"
                        NotificationResult(
                            success = false,
                            messageId = null,
                            failedRecipients = email.to,
                            errorMessage = "Mailjet API error: $errors",
                            metadata = mapOf(
                                "provider" to "mailjet",
                                "status" to (message?.status ?: "unknown"),
                                "errorCode" to (message?.errors?.firstOrNull()?.errorCode?.toString() ?: "unknown")
                            )
                        )
                    }
                } else {
                    val errorBody = response.bodyAsText()
                    log.error("Mailjet API error: {} - {}", response.status, errorBody)
                    NotificationResult(
                        success = false,
                        messageId = null,
                        failedRecipients = email.to,
                        errorMessage = "HTTP ${response.status}: $errorBody",
                        metadata = mapOf(
                            "provider" to "mailjet",
                            "httpStatus" to response.status.value.toString()
                        )
                    )
                }

            } catch (e: ClientRequestException) {
                log.error("Mailjet client request error", e)
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = email.to,
                    errorMessage = "Request failed: ${e.message}",
                    metadata = mapOf(
                        "provider" to "mailjet",
                        "errorType" to "CLIENT_REQUEST_ERROR",
                        "httpStatus" to e.response.status.value.toString()
                    )
                )
            } catch (e: Exception) {
                log.error("Mailjet send error", e)
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = email.to,
                    errorMessage = "Failed to send email: ${e.message}",
                    metadata = mapOf(
                        "provider" to "mailjet",
                        "errorType" to "UNKNOWN_ERROR"
                    )
                )
            }
        }
    }

    /**
     * Send bulk emails using Mailjet batch API
     * Mailjet supports up to 100 messages per request
     */
    override suspend fun sendBulkEmails(emails: List<EmailNotification>): List<NotificationResult> {
        return withContext(Dispatchers.IO) {
            // Mailjet supports up to 100 messages per request
            val batches = emails.chunked(100)
            val results = mutableListOf<NotificationResult>()

            for (batch in batches) {
                try {
                    val messages = batch.map { buildMessage(it) }
                    val request = MailjetSendRequest(messages = messages)

                    val response: HttpResponse = httpClient.post("$baseUrl/send") {
                        basicAuth(apiKey, apiSecret)
                        setBody(request)
                    }

                    if (response.status.isSuccess()) {
                        val sendResponse: MailjetSendResponse = response.body()
                        results.addAll(
                            sendResponse.messages.mapIndexed { index, message ->
                                val originalEmail = batch.getOrNull(index)
                                if (message.status == "success") {
                                    NotificationResult(
                                        success = true,
                                        messageId = message.to.firstOrNull()?.messageId?.toString(),
                                        failedRecipients = emptyList(),
                                        errorMessage = null,
                                        metadata = mapOf(
                                            "provider" to "mailjet",
                                            "messageUuid" to (message.to.firstOrNull()?.messageUuid ?: ""),
                                            "status" to message.status
                                        )
                                    )
                                } else {
                                    NotificationResult(
                                        success = false,
                                        messageId = null,
                                        failedRecipients = originalEmail?.to ?: emptyList(),
                                        errorMessage = message.errors?.firstOrNull()?.errorMessage ?: "Unknown error",
                                        metadata = mapOf(
                                            "provider" to "mailjet",
                                            "status" to message.status
                                        )
                                    )
                                }
                            }
                        )
                    } else {
                        // Batch failed entirely
                        val errorBody = response.bodyAsText()
                        log.error("Mailjet bulk API error: {} - {}", response.status, errorBody)
                        results.addAll(
                            batch.map { email ->
                                NotificationResult(
                                    success = false,
                                    messageId = null,
                                    failedRecipients = email.to,
                                    errorMessage = "Batch failed: HTTP ${response.status}",
                                    metadata = mapOf(
                                        "provider" to "mailjet",
                                        "errorType" to "BATCH_ERROR"
                                    )
                                )
                            }
                        )
                    }
                } catch (e: Exception) {
                    log.error("Mailjet bulk send error", e)
                    results.addAll(
                        batch.map { email ->
                            NotificationResult(
                                success = false,
                                messageId = null,
                                failedRecipients = email.to,
                                errorMessage = "Bulk send failed: ${e.message}",
                                metadata = mapOf(
                                    "provider" to "mailjet",
                                    "errorType" to "BULK_ERROR"
                                )
                            )
                        }
                    )
                }
            }

            results
        }
    }

    /**
     * Send email using Mailjet template
     * Templates are managed via Mailjet UI or API
     */
    override suspend fun sendTemplatedEmail(
        to: List<String>,
        templateId: String,
        templateData: Map<String, String>,
        from: String?,
        subject: String?
    ): NotificationResult {
        return withContext(Dispatchers.IO) {
            try {
                val messages = to.map { recipient ->
                    MailjetMessage(
                        from = MailjetEmail(email = from ?: defaultFrom),
                        to = listOf(MailjetEmail(email = recipient)),
                        templateId = templateId.toIntOrNull(),
                        templateLanguage = true,
                        variables = templateData,
                        subject = subject // Mailjet allows subject override
                    )
                }

                val request = MailjetSendRequest(messages = messages)

                val response: HttpResponse = httpClient.post("$baseUrl/send") {
                    basicAuth(apiKey, apiSecret)
                    setBody(request)
                }

                if (response.status.isSuccess()) {
                    val sendResponse: MailjetSendResponse = response.body()
                    val message = sendResponse.messages.firstOrNull()

                    if (message != null && message.status == "success") {
                        NotificationResult(
                            success = true,
                            messageId = message.to.firstOrNull()?.messageId?.toString(),
                            failedRecipients = emptyList(),
                            errorMessage = null,
                            metadata = mapOf(
                                "provider" to "mailjet",
                                "templateId" to templateId,
                                "status" to message.status
                            )
                        )
                    } else {
                        NotificationResult(
                            success = false,
                            messageId = null,
                            failedRecipients = to,
                            errorMessage = "Template send failed: ${message?.errors?.firstOrNull()?.errorMessage}",
                            metadata = mapOf(
                                "provider" to "mailjet",
                                "templateId" to templateId,
                                "errorType" to "TEMPLATE_ERROR"
                            )
                        )
                    }
                } else {
                    val errorBody = response.bodyAsText()
                    NotificationResult(
                        success = false,
                        messageId = null,
                        failedRecipients = to,
                        errorMessage = "Template send failed: HTTP ${response.status} - $errorBody",
                        metadata = mapOf(
                            "provider" to "mailjet",
                            "templateId" to templateId,
                            "errorType" to "HTTP_ERROR"
                        )
                    )
                }

            } catch (e: Exception) {
                log.error("Mailjet template send error", e)
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = to,
                    errorMessage = "Template send failed: ${e.message}",
                    metadata = mapOf(
                        "provider" to "mailjet",
                        "templateId" to templateId,
                        "errorType" to "UNKNOWN_ERROR"
                    )
                )
            }
        }
    }

    /**
     * Send verification email
     * Creates a custom verification email with link
     */
    override suspend fun sendVerificationEmail(
        email: String,
        verificationToken: String,
        verificationUrl: String
    ): NotificationResult {
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Verify Your Email</title>
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; max-width: 600px; margin: 0 auto; padding: 20px; color: #333; }
                    .container { background: #f9f9f9; border-radius: 8px; padding: 30px; }
                    h2 { color: #2c3e50; }
                    .button { display: inline-block; padding: 14px 28px; background: #3498db; color: white; text-decoration: none; border-radius: 5px; margin: 20px 0; font-weight: bold; }
                    .button:hover { background: #2980b9; }
                    .link-box { background: #ecf0f1; padding: 12px; border-radius: 4px; word-break: break-all; font-size: 13px; color: #7f8c8d; }
                    .footer { margin-top: 30px; font-size: 12px; color: #95a5a6; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h2>Verify Your Email Address</h2>
                    <p>Thank you for signing up with Barter! Please verify your email address by clicking the button below:</p>
                    
                    <a href="$verificationUrl" class="button">Verify Email</a>
                    
                    <p>Or copy and paste this link into your browser:</p>
                    <p class="link-box">$verificationUrl</p>
                    
                    <div class="footer">
                        <p>If you didn't create an account with Barter, you can safely ignore this email.</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val verificationEmail = EmailNotification(
            to = listOf(email),
            from = defaultFrom,
            subject = "Verify Your Email Address on Barter",
            htmlBody = htmlBody,
            textBody = """
                Verify Your Email Address
                
                Thank you for signing up with Barter! Please verify your email by visiting:
                
                $verificationUrl
                
                If you didn't create an account with Barter, you can safely ignore this email.
            """.trimIndent(),
            tags = listOf("verification", "email-confirmation")
        )

        return sendEmail(verificationEmail)
    }

    /**
     * Health check - verifies Mailjet API is accessible
     */
    override suspend fun healthCheck(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Use API key validation endpoint or send a simple GET request
                val response: HttpResponse = httpClient.get("$baseUrl/user") {
                    basicAuth(apiKey, apiSecret)
                }

                val isHealthy = response.status.isSuccess()
                if (isHealthy) {
                    log.info("Mailjet health check passed")
                } else {
                    log.warn("Mailjet health check failed: HTTP {}", response.status)
                }
                isHealthy

            } catch (e: Exception) {
                log.error("Mailjet health check failed", e)
                false
            }
        }
    }

    /**
     * Get send statistics from Mailjet
     */
    suspend fun getSendStatistics(): MailjetStatistics? {
        return withContext(Dispatchers.IO) {
            try {
                val response: HttpResponse = httpClient.get("$baseUrl/statistics") {
                    basicAuth(apiKey, apiSecret)
                }

                if (response.status.isSuccess()) {
                    response.body()
                } else {
                    log.error("Failed to get Mailjet statistics: HTTP {}", response.status)
                    null
                }
            } catch (e: Exception) {
                log.error("Failed to get Mailjet statistics", e)
                null
            }
        }
    }

    /**
     * TODO Close the HTTP client when done
     */
    fun close() {
        try {
            httpClient.close()
        } catch (e: Exception) {
            log.warn("Error closing Mailjet HTTP client", e)
        }
    }

    // ============ Private Helper Methods ============

    private fun buildSendRequest(email: EmailNotification): MailjetSendRequest {
        return MailjetSendRequest(
            messages = listOf(buildMessage(email))
        )
    }

    private fun buildMessage(email: EmailNotification): MailjetMessage {
        return MailjetMessage(
            from = MailjetEmail(
                email = email.from,
                name = email.fromName
            ),
            to = email.to.map { MailjetEmail(email = it) },
            cc = email.cc.takeIf { it.isNotEmpty() }?.map { MailjetEmail(email = it) },
            bcc = email.bcc.takeIf { it.isNotEmpty() }?.map { MailjetEmail(email = it) },
            subject = email.subject,
            textPart = email.textBody ?: stripHtmlTags(email.htmlBody ?: ""),
            htmlPart = email.htmlBody,
            replyTo = email.replyTo?.let { MailjetEmail(email = it) },
            customId = email.metadata["customId"],
            eventPayload = email.metadata["eventPayload"],
            customCampaign = email.tags.firstOrNull()
        )
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }
}

// ============ Mailjet API Data Classes ============

@Serializable
data class MailjetSendRequest(
    val messages: List<MailjetMessage>,
    @SerialName("SandboxMode")
    val sandboxMode: Boolean = false
)

@Serializable
data class MailjetMessage(
    val from: MailjetEmail,
    val to: List<MailjetEmail>,
    val cc: List<MailjetEmail>? = null,
    val bcc: List<MailjetEmail>? = null,
    val subject: String? = null,
    @SerialName("TextPart")
    val textPart: String? = null,
    @SerialName("HTMLPart")
    val htmlPart: String? = null,
    @SerialName("TemplateID")
    val templateId: Int? = null,
    @SerialName("TemplateLanguage")
    val templateLanguage: Boolean? = null,
    @SerialName("Variables")
    val variables: Map<String, String>? = null,
    @SerialName("ReplyTo")
    val replyTo: MailjetEmail? = null,
    @SerialName("Attachments")
    val attachments: List<MailjetAttachment>? = null,
    @SerialName("CustomID")
    val customId: String? = null,
    @SerialName("EventPayload")
    val eventPayload: String? = null,
    @SerialName("CustomCampaign")
    val customCampaign: String? = null
)

@Serializable
data class MailjetEmail(
    val email: String,
    val name: String? = null
)

@Serializable
data class MailjetAttachment(
    @SerialName("ContentType")
    val contentType: String,
    @SerialName("Filename")
    val filename: String,
    @SerialName("Base64Content")
    val base64Content: String
)

@Serializable
data class MailjetSendResponse(
    val messages: List<MailjetMessageResponse>
)

@Serializable
data class MailjetMessageResponse(
    val status: String,
    @SerialName("customID")
    val customId: String? = null,
    val to: List<MailjetRecipientResponse>,
    val cc: List<MailjetRecipientResponse>? = null,
    val bcc: List<MailjetRecipientResponse>? = null,
    val errors: List<MailjetError>? = null
)

@Serializable
data class MailjetRecipientResponse(
    val email: String,
    @SerialName("MessageUUID")
    val messageUuid: String? = null,
    @SerialName("MessageID")
    val messageId: Long? = null,
    @SerialName("MessageHref")
    val messageHref: String? = null
)

@Serializable
data class MailjetError(
    @SerialName("ErrorIdentifier")
    val errorIdentifier: String? = null,
    @SerialName("ErrorCode")
    val errorCode: String? = null,
    @SerialName("StatusCode")
    val statusCode: Int? = null,
    @SerialName("ErrorMessage")
    val errorMessage: String? = null,
    @SerialName("ErrorRelatedTo")
    val errorRelatedTo: List<String>? = null
)

@Serializable
data class MailjetStatistics(
    @SerialName("DeliveredCount")
    val deliveredCount: Int? = null,
    @SerialName("OpenedCount")
    val openedCount: Int? = null,
    @SerialName("ClickedCount")
    val clickedCount: Int? = null,
    @SerialName("BouncedCount")
    val bouncedCount: Int? = null,
    @SerialName("BlockedCount")
    val blockedCount: Int? = null,
    @SerialName("SpamCount")
    val spamCount: Int? = null,
    @SerialName("DeferredCount")
    val deferredCount: Int? = null,
    @SerialName("ProcessedCount")
    val processedCount: Int? = null
)
