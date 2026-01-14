package app.bartering.features.profile.db

import com.pgvector.PGvector
import app.bartering.model.VectorColumnType
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

fun UserSemanticProfilesTable.embeddingField(name: String, dimensions: Int): Column<PGvector> =
    registerColumn(name, VectorColumnType(dimensions)
)

object UserSemanticProfilesTable : Table("user_semantic_profiles") {
    val userId = varchar("user_id", 36)

    val embeddingProfile = embeddingField("embedding_profile", 1024).nullable()
    val embeddingActions = embeddingField("embedding_actions", 1024).nullable()
    val embeddingHaves = embeddingField("embedding_haves", 1024).nullable()
    val embeddingNeeds = embeddingField("embedding_needs", 1024).nullable()

    val hashProfile = varchar("hash_profile", 64).nullable()
    val hashHaves = varchar("hash_haves", 64).nullable()
    val hashNeeds = varchar("hash_needs", 64).nullable()

    val updatedAt = timestamp("updated_at").nullable()

    override val primaryKey = PrimaryKey(userId)
}