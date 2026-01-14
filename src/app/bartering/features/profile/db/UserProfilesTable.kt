package app.bartering.features.profile.db

import io.propertium.gis.point
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import java.time.Instant

object UserProfilesTable : Table("user_profiles") {

    val userId = reference("user_id", UserRegistrationDataTable.id)

    val name = varchar("name", 255).nullable()

    // User's preferred language (ISO 639-1 code: "en", "fr", "lv", "es", etc.)
    // Used for UI localization, matching, and federation
    val preferredLanguage = varchar("preferred_language", 10).default("en")

    // TODO self-description, images, maybe more
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

    // Federation support
    val federationEnabled = bool("federation_enabled").default(true)
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}