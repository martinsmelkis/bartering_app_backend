package app.bartering.features.profile.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object UserPrivacyConsentsTable : Table("user_privacy_consents") {

    val userId = reference("user_id", UserRegistrationDataTable.id)

    // Core consent flags
    val locationConsent = bool("location_consent").default(false)
    val aiProcessingConsent = bool("ai_processing_consent").default(true)
    val analyticsCookiesConsent = bool("analytics_cookies_consent").nullable()
    val federationConsent = bool("federation_consent").default(false)

    // GDPR accountability metadata
    val consentUpdatedAt = timestamp("consent_updated_at").nullable()
    val privacyPolicyVersion = varchar("privacy_policy_version", 50).nullable()
    val privacyPolicyAcceptedAt = timestamp("privacy_policy_accepted_at").nullable()

    override val primaryKey = PrimaryKey(userId)
}
