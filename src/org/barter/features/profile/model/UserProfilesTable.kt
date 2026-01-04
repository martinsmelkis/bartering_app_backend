package org.barter.features.profile.model

import org.barter.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import io.propertium.gis.point
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import java.time.Instant

object UserProfilesTable : Table("user_profiles") {

    val userId = reference("user_id", UserRegistrationDataTable.id)

    val name = varchar("name", 255).nullable()

    // TODO self-description, images, language, rating/reviews/reputation, maybe more
    // A single, indexable geography column is vastly more efficient
    // than separate lat/lon columns. 4326 is the standard SRID for GPS (WGS 84).
    val location = point("location", srid = 4326).nullable()

    // Use exposed-json to automatically serialize/deserialize the map to a JSONB column.
    // This requires the `kotlinx.serialization` plugin and the `exposed-json` dependency.
    val profileKeywordDataMap = jsonb(
        name = "profile_keywords_with_weights",
        serialize = { Json.encodeToString(it) },
        deserialize = {
            val decoded: Map<String, Double> = Json.decodeFromString(it)
            decoded
        }
    ).nullable()

    val lastOnlineTimestamp = timestamp("last_online").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}
