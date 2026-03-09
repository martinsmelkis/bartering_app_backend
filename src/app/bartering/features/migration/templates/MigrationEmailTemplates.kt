package app.bartering.features.migration.templates

import app.bartering.features.notifications.model.EmailNotification

/**
 * Email templates for unified migration system.
 */
object MigrationEmailTemplates {

    /**
     * Recovery email with verification code for lost/broken device scenarios.
     */
    fun createRecoveryEmail(
        recipientEmail: String,
        userId: String,
        recoveryCode: String
    ): EmailNotification {
        // recoveryCode is formatted with dashes (e.g., "D24-S65")

        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: -apple-system, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; color: #333; }
                    .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .code-box { background: #fff; border: 2px solid #667eea; border-radius: 8px; padding: 20px; text-align: center; margin: 20px 0; }
                    .recovery-code { font-family: monospace; font-size: 32px; font-weight: bold; color: #667eea; letter-spacing: 4px; }
                    .warning { background: #fff3cd; border-left: 4px solid #ffc107; padding: 15px; margin: 20px 0; }
                </style>
            </head>
            <body>
                <div class="header"><h1>Device Recovery</h1></div>
                <div class="content">
                    <p>Hello,</p>
                    <p>Use this code to recover your account on your new device:</p>
                    <div class="code-box">
                        <div class="recovery-code">$recoveryCode</div>
                    </div>
                    <div class="warning">
                        <strong>Important:</strong>
                        <ul>
                            <li>Expires in <strong>24 hours</strong></li>
                            <li>Can only be used <strong>once</strong></li>
                            <li>Never share this code</li>
                        </ul>
                    </div>
                    <p>Didn't request this? Someone may be trying to access your account.</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val textBody = """
            DEVICE RECOVERY
            ================

            Use this code to recover your account: $recoveryCode

            IMPORTANT:
            - Expires in 24 hours
            - Single-use only
            - Never share this code

            Didn't request this? Secure your account immediately.
        """.trimIndent()

        return EmailNotification(
            to = listOf(recipientEmail),
            subject = "Account Recovery Code",
            htmlBody = htmlBody,
            textBody = textBody,
            from = "info@bartering.app",
            tags = listOf("migration", "recovery"),
            metadata = mapOf("type" to "email_recovery")
        )
    }
}
