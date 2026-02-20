package app.bartering.features.notifications.templates

import app.bartering.features.notifications.model.EmailNotification

/**
 * Email templates for match notifications
 *
 * These templates notify users when:
 * - A new posting matches their wishlist/interests
 * - A complementary match is found (bartering opportunity)
 * - A similar profile is found (networking opportunity)
 */
object MatchEmailTemplates {

    /**
     * Creates an email notification for when a new posting matches user's interests
     *
     * @param recipientEmail User's email address
     * @param recipientName User's display name
     * @param posterName Name of the user who created the posting
     * @param postingTitle Title/summary of the posting
     * @param postingDescription Full description of what's being offered
     * @param matchReason Why this posting matches (e.g., "matches your interest in photography")
     * @param matchScore How well it matches (0.0-1.0)
     * @param location Optional location of the poster
     * @param postingUrl Deep link to view the posting
     * @param unsubscribeUrl Link to manage notification preferences
     */
    fun createPostingMatchEmail(
        recipientEmail: String,
        recipientName: String,
        posterName: String,
        postingTitle: String,
        postingDescription: String,
        matchReason: String,
        matchScore: Double,
        location: String? = null,
        postingUrl: String,
        unsubscribeUrl: String
    ): EmailNotification {
        val matchPercentage = (matchScore * 100).toInt()

        val subject = "🎯 Great match found! $posterName is offering something you need"

        val htmlBody = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$subject</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                        background: #f5f7fa;
                    }
                    .container {
                        background: white;
                        border-radius: 12px;
                        overflow: hidden;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                    }
                    .header {
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        padding: 30px;
                        text-align: center;
                    }
                    .header h1 {
                        color: white;
                        margin: 0;
                        font-size: 24px;
                        font-weight: 600;
                    }
                    .match-badge {
                        display: inline-block;
                        background: rgba(255,255,255,0.2);
                        color: white;
                        padding: 8px 16px;
                        border-radius: 20px;
                        font-size: 14px;
                        margin-top: 10px;
                    }
                    .content {
                        padding: 30px;
                    }
                    .greeting {
                        font-size: 18px;
                        margin-bottom: 20px;
                        color: #2d3748;
                    }
                    .match-card {
                        background: #f8fafc;
                        border-radius: 8px;
                        padding: 20px;
                        margin: 20px 0;
                        border-left: 4px solid #667eea;
                    }
                    .match-card h2 {
                        margin: 0 0 10px 0;
                        color: #1a202c;
                        font-size: 20px;
                    }
                    .poster-info {
                        display: flex;
                        align-items: center;
                        margin-bottom: 15px;
                    }
                    .poster-avatar {
                        width: 40px;
                        height: 40px;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: white;
                        font-weight: bold;
                        margin-right: 12px;
                        font-size: 16px;
                    }
                    .poster-details {
                        flex: 1;
                    }
                    .poster-name {
                        font-weight: 600;
                        color: #2d3748;
                    }
                    .poster-location {
                        font-size: 13px;
                        color: #718096;
                    }
                    .match-reason {
                        background: #e6fffa;
                        border: 1px solid #81e6d9;
                        border-radius: 6px;
                        padding: 12px;
                        margin: 15px 0;
                        font-size: 14px;
                        color: #234e52;
                    }
                    .match-reason::before {
                        content: "💡 ";
                    }
                    .description {
                        color: #4a5568;
                        margin: 15px 0;
                    }
                    .cta-button {
                        display: inline-block;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                        text-decoration: none;
                        padding: 14px 28px;
                        border-radius: 8px;
                        font-weight: 600;
                        font-size: 16px;
                        margin: 20px 0;
                        text-align: center;
                    }
                    .cta-button:hover {
                        opacity: 0.9;
                    }
                    .secondary-link {
                        display: block;
                        text-align: center;
                        color: #667eea;
                        text-decoration: none;
                        font-size: 14px;
                        margin-top: 10px;
                    }
                    .tips {
                        background: #fffaf0;
                        border-radius: 8px;
                        padding: 20px;
                        margin-top: 25px;
                    }
                    .tips h3 {
                        margin: 0 0 10px 0;
                        color: #744210;
                        font-size: 16px;
                    }
                    .tips ul {
                        margin: 0;
                        padding-left: 20px;
                        color: #975a16;
                        font-size: 14px;
                    }
                    .tips li {
                        margin: 5px 0;
                    }
                    .footer {
                        text-align: center;
                        padding: 20px 30px;
                        border-top: 1px solid #e2e8f0;
                        color: #718096;
                        font-size: 13px;
                    }
                    .footer a {
                        color: #667eea;
                        text-decoration: none;
                    }
                    @media only screen and (max-width: 600px) {
                        body { padding: 10px; }
                        .content { padding: 20px; }
                        .header { padding: 20px; }
                        .header h1 { font-size: 20px; }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🎯 Perfect Match Found!</h1>
                        <div class="match-badge">$matchPercentage% match</div>
                    </div>

                    <div class="content">
                        <div class="greeting">
                            Hi $recipientName,
                        </div>

                        <p>Great news! We found a bartering opportunity that matches what you're looking for.</p>

                        <div class="match-card">
                            <div class="poster-info">
                                <div class="poster-avatar">${posterName.take(1).uppercase()}</div>
                                <div class="poster-details">
                                    <div class="poster-name">$posterName</div>
                                    ${location?.let { "<div class=\"poster-location\">📍 $it</div>" } ?: ""}
                                </div>
                            </div>

                            <h2>${escapeHtml(postingTitle)}</h2>

                            <div class="match-reason">
                                <strong>Why this matches you:</strong> $matchReason
                            </div>

                            <div class="description">
                                ${escapeHtml(postingDescription).replace("\n", "<br>")}
                            </div>
                        </div>

                        <center>
                            <a href="$postingUrl" class="cta-button">View & Connect</a>
                        </center>

                        <a href="$postingUrl" class="secondary-link">Or copy this link: $postingUrl</a>

                        <div class="tips">
                            <h3>💡 Tips for a successful barter:</h3>
                            <ul>
                                <li>Review their profile and offerings before reaching out</li>
                                <li>Be clear about what you can offer in exchange</li>
                                <li>Suggest a specific time and place to meet or arrange delivery</li>
                                <li>After the exchange, leave a review to build trust</li>
                            </ul>
                        </div>
                    </div>

                    <div class="footer">
                        <p>You're receiving this because you enabled match notifications in your Barter account.</p>
                        <p>
                            <a href="$unsubscribeUrl">Manage notification preferences</a> |
                            <a href="$unsubscribeUrl">Unsubscribe</a>
                        </p>
                        <p style="margin-top: 15px; font-size: 12px; color: #a0aec0;">
                            © ${java.time.Year.now().value} Barter. All rights reserved.
                        </p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val textBody = """
            Hi $recipientName,

            Great news! We found a bartering opportunity that matches what you're looking for.

            MATCH: $matchPercentage% compatibility

            $posterName ${location?.let { "from $it" } ?: ""} is offering:
            $postingTitle

            Why this matches you:
            $matchReason

            ${postingDescription.take(200)}${if (postingDescription.length > 200) "..." else ""}

            View the full posting and connect:
            $postingUrl

            ---
            Tips for a successful barter:
            - Review their profile before reaching out
            - Be clear about what you can offer in exchange
            - Suggest a specific time and place
            - Leave a review after the exchange

            Manage your notifications: $unsubscribeUrl
            © ${java.time.Year.now().value} Barter
        """.trimIndent()

        return EmailNotification(
            to = listOf(recipientEmail),
            from = "matches@bartering.app",
            fromName = "Barter Matches",
            subject = subject,
            htmlBody = htmlBody,
            textBody = textBody,
            tags = listOf("match", "posting-match", "barter-opportunity")
        )
    }

    /**
     * Creates an email notification for complementary profile matches
     * (users who have what you need and need what you have)
     */
    fun createComplementaryMatchEmail(
        recipientEmail: String,
        recipientName: String,
        matchedUserName: String,
        whatTheyHave: String,
        whatTheyNeed: String,
        whatYouHave: String,
        whatYouNeed: String,
        matchScore: Double,
        location: String? = null,
        profileUrl: String,
        unsubscribeUrl: String
    ): EmailNotification {
        val matchPercentage = (matchScore * 100).toInt()

        val subject = "🤝 Perfect barter match! $matchedUserName needs what you have"

        val htmlBody = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$subject</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                        background: #f5f7fa;
                    }
                    .container {
                        background: white;
                        border-radius: 12px;
                        overflow: hidden;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                    }
                    .header {
                        background: linear-gradient(135deg, #48bb78 0%, #38a169 100%);
                        padding: 30px;
                        text-align: center;
                    }
                    .header h1 {
                        color: white;
                        margin: 0;
                        font-size: 24px;
                        font-weight: 600;
                    }
                    .match-badge {
                        display: inline-block;
                        background: rgba(255,255,255,0.2);
                        color: white;
                        padding: 8px 16px;
                        border-radius: 20px;
                        font-size: 14px;
                        margin-top: 10px;
                    }
                    .content { padding: 30px; }
                    .greeting { font-size: 18px; margin-bottom: 20px; }
                    .exchange-diagram {
                        background: #f0fff4;
                        border-radius: 12px;
                        padding: 25px;
                        margin: 20px 0;
                        text-align: center;
                    }
                    .exchange-row {
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        margin: 15px 0;
                    }
                    .exchange-item {
                        flex: 1;
                        padding: 15px;
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.05);
                    }
                    .exchange-arrow {
                        font-size: 24px;
                        margin: 0 20px;
                        color: #48bb78;
                    }
                    .person-label {
                        font-size: 12px;
                        color: #718096;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                        margin-bottom: 5px;
                    }
                    .item-name {
                        font-weight: 600;
                        color: #2d3748;
                        font-size: 15px;
                    }
                    .match-highlight {
                        background: #c6f6d5;
                        border: 2px solid #9ae6b4;
                        border-radius: 8px;
                        padding: 15px;
                        margin: 20px 0;
                        text-align: center;
                    }
                    .match-highlight strong {
                        color: #22543d;
                        font-size: 16px;
                    }
                    .cta-button {
                        display: inline-block;
                        background: linear-gradient(135deg, #48bb78 0%, #38a169 100%);
                        color: white;
                        text-decoration: none;
                        padding: 14px 28px;
                        border-radius: 8px;
                        font-weight: 600;
                        font-size: 16px;
                        margin: 20px 0;
                    }
                    .footer {
                        text-align: center;
                        padding: 20px 30px;
                        border-top: 1px solid #e2e8f0;
                        color: #718096;
                        font-size: 13px;
                    }
                    .footer a { color: #48bb78; text-decoration: none; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🤝 Perfect Barter Match!</h1>
                        <div class="match-badge">$matchPercentage% compatibility</div>
                    </div>

                    <div class="content">
                        <div class="greeting">Hi $recipientName,</div>

                        <p>We found someone who has exactly what you're looking for <strong>and</strong> needs what you have!</p>

                        <div class="exchange-diagram">
                            <div class="exchange-row">
                                <div class="exchange-item">
                                    <div class="person-label">You Have</div>
                                    <div class="item-name">${escapeHtml(whatYouHave)}</div>
                                </div>
                                <div class="exchange-arrow">⇄</div>
                                <div class="exchange-item">
                                    <div class="person-label">They Have</div>
                                    <div class="item-name">${escapeHtml(whatTheyHave)}</div>
                                </div>
                            </div>

                            <div style="margin: 15px 0; color: #718096;">↓</div>

                            <div class="exchange-row">
                                <div class="exchange-item">
                                    <div class="person-label">You Need</div>
                                    <div class="item-name">${escapeHtml(whatYouNeed)}</div>
                                </div>
                                <div class="exchange-arrow">⇄</div>
                                <div class="exchange-item">
                                    <div class="person-label">They Need</div>
                                    <div class="item-name">${escapeHtml(whatTheyNeed)}</div>
                                </div>
                            </div>
                        </div>

                        <div class="match-highlight">
                            <strong>💡 This is a perfect complementary match!</strong><br>
                            $matchedUserName ${location?.let { "in $it" } ?: ""} has what you want and wants what you have.
                        </div>

                        <center>
                            <a href="$profileUrl" class="cta-button">View Profile & Connect</a>
                        </center>
                    </div>

                    <div class="footer">
                        <p><a href="$unsubscribeUrl">Manage notifications</a> | <a href="$unsubscribeUrl">Unsubscribe</a></p>
                        <p>© ${java.time.Year.now().value} Barter</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val textBody = """
            Hi $recipientName,

            🤝 PERFECT BARTER MATCH! ($matchPercentage% compatibility)

            We found someone who has exactly what you're looking for AND needs what you have!

            THE EXCHANGE:
            ┌─────────────────┐         ┌─────────────────┐
            │  YOU HAVE       │   ⇄     │  THEY HAVE      │
            │  ${whatYouHave.take(20).padEnd(20)} │         │  ${whatTheyHave.take(20).padEnd(20)} │
            └─────────────────┘         └─────────────────┘
                    ↓                           ↓
            ┌─────────────────┐         ┌─────────────────┐
            │  YOU NEED       │   ⇄     │  THEY NEED      │
            │  ${whatYouNeed.take(20).padEnd(20)} │         │  ${whatTheyNeed.take(20).padEnd(20)} │
            └─────────────────┘         └─────────────────┘

            $matchedUserName ${location?.let { "($it)" } ?: ""} is looking for exactly what you can offer!

            View their profile and start the conversation:
            $profileUrl

            Manage notifications: $unsubscribeUrl
            © ${java.time.Year.now().value} Barter
        """.trimIndent()

        return EmailNotification(
            to = listOf(recipientEmail),
            from = "matches@bartering.app",
            fromName = "Barter Matches",
            subject = subject,
            htmlBody = htmlBody,
            textBody = textBody,
            tags = listOf("match", "complementary-match", "perfect-barter")
        )
    }

    /**
     * Creates an email notification for similar profile matches
     * (users with similar interests for networking)
     */
    fun createSimilarProfileMatchEmail(
        recipientEmail: String,
        recipientName: String,
        matchedUserName: String,
        sharedInterests: List<String>,
        matchScore: Double,
        location: String? = null,
        profileUrl: String,
        unsubscribeUrl: String
    ): EmailNotification {
        val matchPercentage = (matchScore * 100).toInt()
        val interestsList = sharedInterests.take(5).joinToString(", ")

        val subject = "👥 Someone with similar interests joined Barter!"

        val interestsHtml = sharedInterests.take(5).joinToString("", prefix = "<ul>", postfix = "</ul>") {
            "<li>✨ ${escapeHtml(it)}</li>"
        }

        val htmlBody = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$subject</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                        background: #f5f7fa;
                    }
                    .container {
                        background: white;
                        border-radius: 12px;
                        overflow: hidden;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                    }
                    .header {
                        background: linear-gradient(135deg, #ed8936 0%, #dd6b20 100%);
                        padding: 30px;
                        text-align: center;
                    }
                    .header h1 { color: white; margin: 0; font-size: 24px; }
                    .content { padding: 30px; }
                    .interests-box {
                        background: #fffaf0;
                        border-radius: 8px;
                        padding: 20px;
                        margin: 20px 0;
                    }
                    .interests-box ul {
                        margin: 0;
                        padding-left: 25px;
                    }
                    .interests-box li {
                        margin: 8px 0;
                        color: #744210;
                    }
                    .cta-button {
                        display: inline-block;
                        background: linear-gradient(135deg, #ed8936 0%, #dd6b20 100%);
                        color: white;
                        text-decoration: none;
                        padding: 14px 28px;
                        border-radius: 8px;
                        font-weight: 600;
                        margin: 20px 0;
                    }
                    .footer {
                        text-align: center;
                        padding: 20px 30px;
                        border-top: 1px solid #e2e8f0;
                        color: #718096;
                        font-size: 13px;
                    }
                    .footer a { color: #ed8936; text-decoration: none; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>👥 Similar Interests Found!</h1>
                    </div>

                    <div class="content">
                        <p>Hi $recipientName,</p>

                        <p>We found someone who shares your interests! <strong>$matchedUserName</strong> ${location?.let { "from $it" } ?: ""} joined Barter and has similar passions.</p>

                        <div class="interests-box">
                            <strong>Shared interests ($matchPercentage% match):</strong>
                            $interestsHtml
                        </div>

                        <p>This could be a great opportunity to connect, share knowledge, or find new bartering opportunities!</p>

                        <center>
                            <a href="$profileUrl" class="cta-button">View Profile</a>
                        </center>
                    </div>

                    <div class="footer">
                        <p><a href="$unsubscribeUrl">Manage notifications</a></p>
                        <p>© ${java.time.Year.now().value} Barter</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val textBody = """
            Hi $recipientName,

            👥 Someone with similar interests joined Barter!

            $matchedUserName shares your passion for:
            ${sharedInterests.joinToString("\n") { "  • $it" }}

            Match score: $matchPercentage%
            ${location?.let { "Location: $it" } ?: ""}

            View their profile: $profileUrl

            This could be a great networking opportunity!

            Manage notifications: $unsubscribeUrl
            © ${java.time.Year.now().value} Barter
        """.trimIndent()

        return EmailNotification(
            to = listOf(recipientEmail),
            from = "matches@bartering.app",
            fromName = "Barter Community",
            subject = subject,
            htmlBody = htmlBody,
            textBody = textBody,
            tags = listOf("match", "similar-profile", "networking")
        )
    }

    /**
     * Helper function to escape HTML special characters
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }
}
