package app.bartering.features.notifications.service.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.bartering.features.notifications.model.EmailNotification
import app.bartering.features.notifications.model.NotificationResult
import app.bartering.features.notifications.service.EmailService
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.*

/**
 * AWS SES (Simple Email Service) implementation
 *
 * Configuration:
 * - AWS Credentials: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY (or environment variables)
 * - Region: AWS_REGION (e.g., us-east-1)
 * - Verified sender emails/domains (required before sending)
 *
 * Features:
 * - High deliverability
 * - Template support (SES templates stored in AWS)
 * - Configuration sets for tracking
 * - Bounce/complaint handling
 * - Bulk email capabilities
 *
 * SDK: software.amazon.awssdk:ses
 *
 * Important Notes:
 * - In sandbox mode, you can only send to verified email addresses
 * - Request production access to send to any email address
 * - Configure SPF/DKIM/DMARC for better deliverability
 *
 * Environment Variables Required:
 * - AWS_ACCESS_KEY_ID (or use IAM roles on EC2/ECS)
 * - AWS_SECRET_ACCESS_KEY (or use IAM roles on EC2/ECS)
 * - AWS_REGION (default: us-east-1)
 */
class AwsSesEmailService(
    private val region: String = System.getenv("AWS_REGION") ?: "us-east-1",
    private val defaultFrom: String = System.getenv("AWS_SES_FROM_EMAIL") ?: "noreply@bartering.app",
    private val configurationSetName: String? = System.getenv("AWS_SES_CONFIGURATION_SET")
) : EmailService {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val sesClient: SesClient by lazy {
        val credentialsProvider = try {
            // Try to use environment variables first
            val accessKeyId = System.getenv("AWS_ACCESS_KEY_ID")
            val secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY")

            if (accessKeyId != null && secretAccessKey != null) {
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                )
            } else {
                // Fall back to default credential chain (IAM role, EC2 metadata, etc.)
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("dummy", "dummy") // Will be overridden by default chain
                )
            }
        } catch (_: Exception) {
            null
        }

        val builder = SesClient.builder()
            .region(Region.of(region))

        // Only set credentials provider if we have explicit credentials
        if (credentialsProvider != null &&
            System.getenv("AWS_ACCESS_KEY_ID") != null &&
            System.getenv("AWS_SECRET_ACCESS_KEY") != null) {
            builder.credentialsProvider(credentialsProvider)
        }

        builder.build()
    }

    /**
     * Send a single email
     */
    override suspend fun sendEmail(email: EmailNotification): NotificationResult {
        return withContext(Dispatchers.IO) {
            try {
                val destination = Destination.builder()
                    .toAddresses(email.to)
                    .ccAddresses(email.cc)
                    .bccAddresses(email.bcc)
                    .build()

                val subject = Content.builder()
                    .data(email.subject)
                    .charset("UTF-8")
                    .build()

                val bodyBuilder = Body.builder()

                // Add HTML body if provided
                if (email.htmlBody != null) {
                    bodyBuilder.html(
                        Content.builder()
                            .data(email.htmlBody)
                            .charset("UTF-8")
                            .build()
                    )
                }

                // Add text body if provided (fallback)
                if (email.textBody != null) {
                    bodyBuilder.text(
                        Content.builder()
                            .data(email.textBody)
                            .charset("UTF-8")
                            .build()
                    )
                } else if (email.htmlBody == null) {
                    // If no text body, strip HTML tags as fallback
                    bodyBuilder.text(
                        Content.builder()
                            .data(stripHtmlTags(email.subject))
                            .charset("UTF-8")
                            .build()
                    )
                }

                val message = Message.builder()
                    .subject(subject)
                    .body(bodyBuilder.build())
                    .build()

                val sendEmailRequestBuilder = SendEmailRequest.builder()
                    .source(formatFromAddress(email.from, email.fromName))
                    .destination(destination)
                    .message(message)

                // Add reply-to if specified
                if (email.replyTo != null) {
                    sendEmailRequestBuilder.replyToAddresses(email.replyTo)
                }

                // Add configuration set if specified
                if (configurationSetName != null) {
                    sendEmailRequestBuilder.configurationSetName(configurationSetName)
                }

                // Note: Custom headers are not directly supported in SendEmailRequest.
                // To add custom headers, use configuration sets with SNS notifications
                // or construct a raw MIME message using SendRawEmailRequest.

                // Add tags for tracking
                if (email.tags.isNotEmpty()) {
                    sendEmailRequestBuilder.tags(
                        email.tags.map { tag ->
                            MessageTag.builder().name(tag).value("true").build()
                        }
                    )
                }

                val response = sesClient.sendEmail(sendEmailRequestBuilder.build())

                NotificationResult(
                    success = true,
                    messageId = response.messageId(),
                    failedRecipients = emptyList(),
                    errorMessage = null,
                    metadata = mapOf(
                        "requestId" to response.responseMetadata().requestId(),
                        "provider" to "aws-ses",
                        "region" to region
                    )
                )

            } catch (e: MessageRejectedException) {
                log.error("AWS SES Message Rejected", e)
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = email.to,
                    errorMessage = "Message rejected: ${e.message}",
                    metadata = mapOf(
                        "provider" to "aws-ses",
                        "errorType" to "MESSAGE_REJECTED",
                        " requestId" to e.requestId()
                    )
                )

            } catch (e: MailFromDomainNotVerifiedException) {
                log.error("AWS SES Domain Not Verified", e)
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = email.to,
                    errorMessage = "Sender domain not verified in AWS SES: ${e.message}",
                    metadata = mapOf(
                        "provider" to "aws-ses",
                        "errorType" to "DOMAIN_NOT_VERIFIED"
                    )
                )

            } catch (e: Exception) {
                log.error("AWS SES Send Error", e)
                e.printStackTrace()
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = email.to,
                    errorMessage = "Failed to send email: ${e.message}",
                    metadata = mapOf(
                        "provider" to "aws-ses",
                        "errorType" to "UNKNOWN_ERROR"
                    )
                )
            }
        }
    }

    /**
     * Send bulk emails using SES bulk templating
     * For non-template emails, sends individually
     */
    override suspend fun sendBulkEmails(emails: List<EmailNotification>): List<NotificationResult> {
        return withContext(Dispatchers.IO) {
            emails.map { email ->
                sendEmail(email)
            }
        }
    }

    /**
     * Send email using a template stored in AWS SES
     * Templates must be created in the AWS SES console first
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
                // Convert templateData Map to JSON string
                val templateDataJson = templateData.entries
                    .joinToString(",", "{", "}") { (key, value) ->
                        "\"$key\":\"${value.replace("\"", "\\\"")}\""
                    }

                val destination = Destination.builder()
                    .toAddresses(to)
                    .build()

                val requestBuilder = SendTemplatedEmailRequest.builder()
                    .source(from ?: defaultFrom)
                    .destination(destination)
                    .template(templateId)
                    .templateData(templateDataJson)

                // Add configuration set if specified
                if (configurationSetName != null) {
                    requestBuilder.configurationSetName(configurationSetName)
                }

                // Note: Override template subject if provided
                if (subject != null) {
                    // SES doesn't support overriding template subject via API directly
                    // You would need to create a template with a placeholder subject
                }

                val response = sesClient.sendTemplatedEmail(requestBuilder.build())

                NotificationResult(
                    success = true,
                    messageId = response.messageId(),
                    failedRecipients = emptyList(),
                    errorMessage = null,
                    metadata = mapOf(
                        "templateId" to templateId,
                        "requestId" to response.responseMetadata().requestId(),
                        "provider" to "aws-ses"
                    )
                )

            } catch (e: TemplateDoesNotExistException) {
                log.error("AWS SES Template Not Found: {}", templateId)
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = to,
                    errorMessage = "Template '$templateId' not found in AWS SEs",
                    metadata = mapOf(
                        "provider" to "aws-ses",
                        "errorType" to "TEMPLATE_NOT_FOUND",
                        "templateId" to templateId
                    )
                )

            } catch (e: Exception) {
                log.error("AWS SES Template Error", e)
                e.printStackTrace()
                NotificationResult(
                    success = false,
                    messageId = null,
                    failedRecipients = to,
                    errorMessage = "Failed to send templated email: ${e.message}",
                    metadata = mapOf(
                        "provider" to "aws-ses",
                        "errorType" to "TEMPLATE_ERROR"
                    )
                )
            }
        }
    }

    /**
     * Send verification email
     * Note: SES handles email verification via VerifyEmailIdentity API
     * This is for custom verification links (e.g., account verification)
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
                    body { font-family: Arial, sans-serif; line-height: 1.6; max-width: 600px; margin: 0 auto; padding: 20px; }
                    .button { display: inline-block; padding: 12px 24px; background: #007bff; color: white; text-decoration: none; border-radius: 4px; margin: 20px 0; }
                    .code { background: #f4f4f4; padding: 10px; border-radius: 4px; font-family: monospace; font-size: 18px; }
                </style>
            </head>
            <body>
                <h2>Verify Your Email Address</h2>
                <p>Thank you for signing up with Barter! Please verify your email address by clicking the button below:</p>
                
                <a href="$verificationUrl" class="button">Verify Email</a>
                
                <p>Or copy and paste this link into your browser:</p>
                <p><small>$verificationUrl</small></p>
                
                <p>If you didn't create an account with Barter, you can safely ignore this email.</p>
            </body>
            </html>
        """.trimIndent()

        val verificationEmail = EmailNotification(
            to = listOf(email),
            from = defaultFrom,
            subject = "Verify Your Email Address on Barter",
            htmlBody = htmlBody,
            textBody = "Please verify your email by visiting: $verificationUrl",
            tags = listOf("verification", "email-confirmation")
        )

        return sendEmail(verificationEmail)
    }

    /**
     * Health check - verifies SES service is accessible
     */
    override suspend fun healthCheck(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Use GetSendQuota as a simple health check
                val response = sesClient.getSendQuota()
                log.info("AWS SES Health Check - Max24HourSend: {}", response.max24HourSend())
                true

            } catch (e: Exception) {
                log.error("AWS SES Health Check Failed", e)
                false
            }
        }
    }

    /**
     * Verify an email address in AWS SES (needs to be done before sending to unverified addresses in sandbox mode)
     */
    suspend fun verifyEmailIdentity(email: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = VerifyEmailIdentityRequest.builder()
                    .emailAddress(email)
                    .build()

                sesClient.verifyEmailIdentity(request)
                log.info("Verification email sent to: {}", email)
                true

            } catch (e: Exception) {
                log.error("Failed to verify email identity", e)
                false
            }
        }
    }

    /**
     * Verify a domain in AWS SES
     */
    suspend fun verifyDomainIdentity(domain: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = VerifyDomainIdentityRequest.builder()
                    .domain(domain)
                    .build()

                val response = sesClient.verifyDomainIdentity(request)
                log.info("Domain verification initiated for: {}", domain)
                log.info("DNS Record to add: {}", response.verificationToken())
                true

            } catch (e: Exception) {
                log.error("Failed to verify domain identity", e)
                false
            }
        }
    }

    /**
     * Get current SES send quota
     */
    suspend fun getSendQuota(): GetSendQuotaResponse {
        return withContext(Dispatchers.IO) {
            sesClient.getSendQuota()
        }
    }

    /**
     * Check if an email identity is verified
     */
    suspend fun isIdentityVerified(identity: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = GetIdentityVerificationAttributesRequest.builder()
                    .identities(identity)
                    .build()

                val response = sesClient.getIdentityVerificationAttributes(request)

                val verificationAttributes = response.verificationAttributes()[identity]
                val status = verificationAttributes?.verificationStatus()

                status == VerificationStatus.SUCCESS

            } catch (e: Exception) {
                log.error("Failed to check identity verification", e)
                false
            }
        }
    }

    /**
     * Format the "From" address with optional display name
     */
    private fun formatFromAddress(email: String, name: String?): String {
        return if (name != null && name.isNotBlank()) {
            "\"$name\" <$email>"
        } else {
            email
        }
    }

    /**
     * Strip HTML tags for plain text fallback
     */
    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
    }

    /**
     * Close the SES client when done
     */
    fun close() {
        try {
            sesClient.close()
        } catch (e: Exception) {
            log.warn("Error closing SES client", e)
        }
    }
}
