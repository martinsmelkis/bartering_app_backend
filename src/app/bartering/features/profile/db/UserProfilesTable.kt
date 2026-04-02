package app.bartering.features.profile.db

import app.bartering.features.reviews.model.AccountType
import app.bartering.model.point
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import java.time.Instant

object UserProfilesTable : Table("user_profiles") {

    val userId = reference("user_id", UserRegistrationDataTable.id)

    val name = varchar("name", 255).nullable()

    // User's preferred language (ISO 639-1 code) for UI localization, matching, and federation
    val preferredLanguage = varchar("preferred_language", 10).default("en")

    val selfDescription = varchar("self_description", 128).nullable()
    val accountType = enumerationByName("account_type", 32, AccountType::class).default(AccountType.INDIVIDUAL)
    // Inline SVG text content for profile avatar icon
    val profileAvatarIcon = text("profile_avatar_icon").nullable()

    // URLs to personal work reference images
    val workReferenceImageUrls = jsonb<List<String>>(
        name = "work_reference_image_urls",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString(it) }
    ).default(emptyList())

    // A single, indexable geography column is vastly more efficient
    // than separate lat/lon columns. 4326 is the standard SRID for GPS (WGS 84).
    val location = point("location", srid = 4326).nullable()

    // Use exposed-json to automatically serialize/deserialize the map to a JSONB column.
    // This requires the `kotlinx.serialization` plugin and the `exposed-json` dependency.
    val profileKeywordDataMap = jsonb(
        name = "profile_keywords_with_weights",
        serialize = { Json.encodeToString(it) },
        deserialize = {
            val decoded: Map<String, Double> = Json.Default.decodeFromString(it)
            decoded
        }
    ).nullable()

    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}