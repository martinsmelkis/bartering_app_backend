package app.bartering.features.profile.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object UserRegistrationDataTable : Table("user_registration_data") {
    val id = varchar("id", length = 50)
    val publicKey = varchar("public_key", 2500)

    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}