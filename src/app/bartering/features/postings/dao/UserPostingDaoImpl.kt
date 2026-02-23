package app.bartering.features.postings.dao

import app.bartering.config.AiConfig
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.postings.db.PostingAttributesLinkTable
import app.bartering.features.postings.db.UserPostingsTable
import app.bartering.features.postings.model.*
import app.bartering.features.postings.service.ImageStorageService
import app.bartering.utils.SecurityUtils
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

class UserPostingDaoImpl : UserPostingDao {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val imageStorage: ImageStorageService by inject(ImageStorageService::class.java)

    override suspend fun createPosting(userId: String, request: UserPostingRequest): String? {
        return try {
            val postingId = request.id ?: UUID.randomUUID().toString()
            val now = Instant.now()
            
            // Set default expiry to 30 days if not provided (GDPR data minimization)
            val expiryDate = request.expiresAt ?: now.plusSeconds(30L * 24 * 60 * 60) // 30 days

            // First, create the posting in a transaction
            dbQuery {
                UserPostingsTable.insert {
                    it[id] = postingId
                    it[UserPostingsTable.userId] = userId
                    it[title] = request.title
                    it[description] = request.description
                    it[value] = request.value?.toBigDecimal()
                    it[expiresAt] = expiryDate
                    it[imageUrls] = request.imageUrls
                    it[isOffer] = request.isOffer
                    it[status] = PostingStatus.ACTIVE.name.lowercase()
                    it[createdAt] = now
                    it[updatedAt] = now
                }

                // Insert attribute links
                if (request.attributes.isNotEmpty()) {
                    PostingAttributesLinkTable.batchInsert(request.attributes) { attr ->
                        this[PostingAttributesLinkTable.postingId] = postingId
                        this[PostingAttributesLinkTable.attributeId] = attr.attributeId
                        this[PostingAttributesLinkTable.relevancy] = attr.relevancy.toBigDecimal()
                    }
                }
            }

            // After transaction commits, generate embedding in separate transaction
            log.debug("Created posting with ID: {}, now generating embedding", postingId)
            updatePostingEmbedding(postingId)

            postingId
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun updatePosting(
        userId: String,
        postingId: String,
        request: UserPostingRequest
    ): Boolean {
        // Update the posting in a transaction
        val success = dbQuery {
            // Verify ownership
            UserPostingsTable
                .select(UserPostingsTable.userId)
                .where { (UserPostingsTable.id eq postingId) and (UserPostingsTable.userId eq userId) }
                .singleOrNull() ?: return@dbQuery false

            UserPostingsTable.update({ UserPostingsTable.id eq postingId }) {
                it[title] = request.title
                it[description] = request.description
                it[value] = request.value?.toBigDecimal()
                it[expiresAt] = request.expiresAt
                it[imageUrls] = request.imageUrls
                it[isOffer] = request.isOffer
            }

            // Update attribute links
            PostingAttributesLinkTable.deleteWhere { PostingAttributesLinkTable.postingId eq postingId }
            if (request.attributes.isNotEmpty()) {
                PostingAttributesLinkTable.batchInsert(request.attributes) { attr ->
                    this[PostingAttributesLinkTable.postingId] = postingId
                    this[PostingAttributesLinkTable.attributeId] = attr.attributeId
                    this[PostingAttributesLinkTable.relevancy] = attr.relevancy.toBigDecimal()
                }
            }

            true
        }

        // After transaction commits, regenerate embedding in separate transaction
        if (success) {
            log.debug("Updated posting with ID: {}, now regenerating embedding", postingId)
            updatePostingEmbedding(postingId)
        }

        return success
    }

    override suspend fun deletePosting(userId: String, postingId: String): Boolean = dbQuery {
        val updated = UserPostingsTable.update({
            (UserPostingsTable.id eq postingId) and (UserPostingsTable.userId eq userId)
        }) {
            it[status] = PostingStatus.DELETED.name.lowercase()
        }
        updated > 0
    }

    override suspend fun getPosting(postingId: String): UserPosting? = dbQuery {
        val join = UserPostingsTable
            .join(
                PostingAttributesLinkTable, JoinType.LEFT,
                UserPostingsTable.id, PostingAttributesLinkTable.postingId
            )

        val results = join.selectAll().where { UserPostingsTable.id eq postingId }.toList()

        if (results.isEmpty()) return@dbQuery null

        toUserPosting(results.first(), results)
    }

    override suspend fun getUserPostings(
        userId: String,
        includeExpired: Boolean
    ): List<UserPosting> = dbQuery {
        val join = UserPostingsTable
            .join(
                PostingAttributesLinkTable, JoinType.LEFT,
                UserPostingsTable.id, PostingAttributesLinkTable.postingId
            )

        val query = join.selectAll().where { UserPostingsTable.userId eq userId }

        val filteredQuery = if (!includeExpired) {
            query.andWhere { UserPostingsTable.status eq "active" }
        } else {
            query
        }

        val results = filteredQuery.orderBy(UserPostingsTable.createdAt, SortOrder.DESC).toList()

        // Group by posting ID
        results.groupBy { it[UserPostingsTable.id] }
            .map { (_, rows) -> toUserPosting(rows.first(), rows) }
    }

    override suspend fun getAllPostings(includeExpired: Boolean): List<UserPosting> = dbQuery {
        val join = UserPostingsTable
            .join(
                PostingAttributesLinkTable, JoinType.LEFT,
                UserPostingsTable.id, PostingAttributesLinkTable.postingId
            )

        val query = join.selectAll()

        val filteredQuery = if (!includeExpired) {
            query.where { UserPostingsTable.status eq "active" }
                .andWhere { 
                    (UserPostingsTable.expiresAt.isNull()) or 
                    (UserPostingsTable.expiresAt greater Instant.now()) 
                }
        } else {
            query
        }

        val results = filteredQuery.orderBy(UserPostingsTable.createdAt, SortOrder.DESC).toList()

        // Group by posting ID
        results.groupBy { it[UserPostingsTable.id] }
            .map { (_, rows) -> toUserPosting(rows.first(), rows) }
    }

    override suspend fun getNearbyPostings(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        isOffer: Boolean?,
        excludeUserId: String?,
        limit: Int
    ): List<UserPostingWithDistance> = dbQuery {

        if (excludeUserId != null && !SecurityUtils.isValidUUID(excludeUserId)) {
            log.warn("Invalid excludeUserId format: {}", excludeUserId)
            return@dbQuery emptyList()
        }

        val nearbyQuery = """
            SELECT
                p.id,
                p.user_id,
                p.title,
                p.description,
                p.value,
                p.expires_at,
                p.image_urls::text,
                p.is_offer,
                p.status,
                p.created_at,
                p.updated_at,
                ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters
            FROM
                user_postings p
            INNER JOIN user_profiles up ON p.user_id = up.user_id
            WHERE
                p.status = 'active'
                AND ST_DWithin(up.location::geography, ST_MakePoint(?, ?)::geography, ?)
                ${if (isOffer != null) "AND p.is_offer = ?" else ""}
                ${if (excludeUserId != null) "AND p.user_id != ?" else ""}
                AND (p.expires_at IS NULL OR p.expires_at > NOW())
            ORDER BY
                distance_meters ASC
            LIMIT ?;
        """.trimIndent()

        val queryParams = mutableListOf<Pair<IColumnType<*>, Any?>>()
        queryParams.add(DoubleColumnType() to longitude)
        queryParams.add(DoubleColumnType() to latitude)
        queryParams.add(DoubleColumnType() to longitude)
        queryParams.add(DoubleColumnType() to latitude)
        queryParams.add(DoubleColumnType() to radiusMeters)

        if (isOffer != null) {
            queryParams.add(BooleanColumnType() to isOffer)
        }
        if (excludeUserId != null) {
            queryParams.add(VarCharColumnType() to excludeUserId)
        }
        queryParams.add(IntegerColumnType() to limit)

        val postings = mutableListOf<UserPostingWithDistance>()
        val postingAttributes = mutableMapOf<String, MutableList<PostingAttributeDto>>()

        TransactionManager.current().connection.prepareStatement(nearbyQuery, false)
            .also { statement ->
                queryParams.forEachIndexed { index, (_, value) ->
                    statement[index + 1] = value.toString()
                }

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val postingId = rs.getString("id")
                    val distanceMeters = rs.getDouble("distance_meters")

                    val posting = UserPosting(
                        id = postingId,
                        userId = rs.getString("user_id"),
                        title = rs.getString("title"),
                        description = rs.getString("description"),
                        value = rs.getBigDecimal("value")?.toDouble(),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                        imageUrls = parseImageUrls(rs.getString("image_urls")),
                        isOffer = rs.getBoolean("is_offer"),
                        status = PostingStatus.valueOf(rs.getString("status").uppercase()),
                        attributes = emptyList(),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        updatedAt = rs.getTimestamp("updated_at").toInstant()
                    )

                    postings.add(
                        UserPostingWithDistance(
                            posting = posting,
                            distanceKm = distanceMeters / 1000,
                            similarityScore = null
                        )
                    )
                    postingAttributes[postingId] = mutableListOf()
                }
            }

        // Fetch attributes for all postings
        if (postings.isNotEmpty()) {
            val postingIds = postings.map { it.posting.id }
            PostingAttributesLinkTable
                .selectAll()
                .where { PostingAttributesLinkTable.postingId inList postingIds }
                .forEach { row ->
                    val postingId = row[PostingAttributesLinkTable.postingId]
                    postingAttributes[postingId]?.add(
                        PostingAttributeDto(
                            attributeId = row[PostingAttributesLinkTable.attributeId],
                            relevancy = row[PostingAttributesLinkTable.relevancy].toDouble()
                        )
                    )
                }
        }

        postings.map { postingWithDistance ->
            postingWithDistance.copy(
                posting = postingWithDistance.posting.copy(
                    attributes = postingAttributes[postingWithDistance.posting.id] ?: emptyList()
                )
            )
        }
    }

    override suspend fun searchPostings(
        searchText: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?,
        isOffer: Boolean?,
        limit: Int
    ): List<UserPostingWithDistance> = dbQuery {

        if (!SecurityUtils.isValidLength(searchText, 1, 1000)) {
            log.warn("Invalid search text length: {}", searchText.length)
            return@dbQuery emptyList()
        }

        if (SecurityUtils.containsSqlInjectionPatterns(searchText)) {
            log.warn("Search text contains dangerous patterns")
            return@dbQuery emptyList()
        }

        val sanitizedSearchText = SecurityUtils.sanitizeSqlString(searchText)

        val semanticSearchQuery = """
            WITH search_embedding AS (
                SELECT ai.ollama_embed('${AiConfig.embedModel}', ?, host => '${AiConfig.ollamaHost}') as embedding
            )
            SELECT
                p.id,
                p.user_id,
                p.title,
                p.description,
                p.value,
                p.expires_at,
                p.image_urls::text,
                p.is_offer,
                p.status,
                p.created_at,
                p.updated_at,
                COALESCE(1 - (p.embedding <=> (SELECT embedding FROM search_embedding)), 0.0) as similarity_score
                ${
            if (latitude != null && longitude != null) {
                ", ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters"
            } else {
                ", NULL as distance_meters"
            }
        }
            FROM
                user_postings p
            ${
            if (latitude != null && longitude != null) {
                "INNER JOIN user_profiles up ON p.user_id = up.user_id"
            } else {
                ""
            }
        }
            WHERE
                p.status = 'active'
                AND p.embedding IS NOT NULL
                AND (p.expires_at IS NULL OR p.expires_at > NOW())
                ${if (isOffer != null) "AND p.is_offer = ?" else ""}
                ${
            if (latitude != null && longitude != null && radiusMeters != null) {
                "AND ST_DWithin(up.location::geography, ST_MakePoint(?, ?)::geography, ?)"
            } else {
                ""
            }
        }
            ORDER BY
                similarity_score DESC,
                distance_meters ASC NULLS LAST
            LIMIT ?;
        """.trimIndent()

        val queryParams = mutableListOf<Pair<IColumnType<*>, Any?>>()
        queryParams.add(VarCharColumnType() to sanitizedSearchText)

        if (latitude != null && longitude != null) {
            queryParams.add(DoubleColumnType() to longitude)
            queryParams.add(DoubleColumnType() to latitude)
        }

        if (isOffer != null) {
            queryParams.add(BooleanColumnType() to isOffer)
        }

        if (latitude != null && longitude != null && radiusMeters != null) {
            queryParams.add(DoubleColumnType() to longitude)
            queryParams.add(DoubleColumnType() to latitude)
            queryParams.add(DoubleColumnType() to radiusMeters)
        }

        queryParams.add(IntegerColumnType() to limit)

        val postings = mutableListOf<UserPostingWithDistance>()
        val postingAttributes = mutableMapOf<String, MutableList<PostingAttributeDto>>()

        TransactionManager.current().connection.prepareStatement(semanticSearchQuery, false)
            .also { statement ->
                queryParams.forEachIndexed { index, (_, value) ->
                    statement[index + 1] = value ?: ""
                }

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val postingId = rs.getString("id")
                    val similarityScore = rs.getDouble("similarity_score")
                    val distanceMeters = rs.getDouble("distance_meters")

                    if (similarityScore < 0.5) continue

                    val posting = UserPosting(
                        id = postingId,
                        userId = rs.getString("user_id"),
                        title = rs.getString("title"),
                        description = rs.getString("description"),
                        value = rs.getBigDecimal("value")?.toDouble(),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                        imageUrls = parseImageUrls(rs.getString("image_urls")),
                        isOffer = rs.getBoolean("is_offer"),
                        status = PostingStatus.valueOf(rs.getString("status").uppercase()),
                        attributes = emptyList(),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        updatedAt = rs.getTimestamp("updated_at").toInstant()
                    )

                    postings.add(
                        UserPostingWithDistance(
                            posting = posting,
                            distanceKm = if (distanceMeters != 0.0) distanceMeters / 1000 else null,
                            similarityScore = similarityScore
                        )
                    )
                    postingAttributes[postingId] = mutableListOf()
                }
            }

        // Fetch attributes for all postings
        if (postings.isNotEmpty()) {
            val postingIds = postings.map { it.posting.id }
            PostingAttributesLinkTable
                .selectAll()
                .where { PostingAttributesLinkTable.postingId inList postingIds }
                .forEach { row ->
                    val postingId = row[PostingAttributesLinkTable.postingId]
                    postingAttributes[postingId]?.add(
                        PostingAttributeDto(
                            attributeId = row[PostingAttributesLinkTable.attributeId],
                            relevancy = row[PostingAttributesLinkTable.relevancy].toDouble()
                        )
                    )
                }
        }

        postings.map { postingWithDistance ->
            postingWithDistance.copy(
                posting = postingWithDistance.posting.copy(
                    attributes = postingAttributes[postingWithDistance.posting.id] ?: emptyList()
                )
            )
        }
    }

    override suspend fun getMatchingPostings(
        userId: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?,
        limit: Int
    ): List<UserPostingWithDistance> = dbQuery {
        if (!SecurityUtils.isValidUUID(userId)) {
            log.warn("Invalid userId format: {}", userId)
            return@dbQuery emptyList()
        }

        // This query finds postings that semantically match the user's profile
        // based on their semantic embeddings (needs and haves)
        val matchingQuery = """
            WITH user_semantic AS (
                SELECT embedding_haves, embedding_needs
                FROM user_semantic_profiles
                WHERE user_id = ?
            )
            SELECT
                p.id,
                p.user_id,
                p.title,
                p.description,
                p.value,
                p.expires_at,
                p.image_urls::text,
                p.is_offer,
                p.status,
                p.created_at,
                p.updated_at,
                -- Calculate match score: user's needs vs posting offers, or user's offers vs posting needs
                GREATEST(
                    COALESCE(1 - (p.embedding <=> (SELECT embedding_needs FROM user_semantic)), 0.0),
                    COALESCE(1 - (p.embedding <=> (SELECT embedding_haves FROM user_semantic)), 0.0)
                ) as match_score
                ${
            if (latitude != null && longitude != null) {
                ", ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters"
            } else {
                ", NULL as distance_meters"
            }
        }
            FROM
                user_postings p
            ${
            if (latitude != null && longitude != null) {
                "INNER JOIN user_profiles up ON p.user_id = up.user_id"
            } else {
                ""
            }
        }
            WHERE
                p.status = 'active'
                AND p.user_id != ?
                AND p.embedding IS NOT NULL
                AND (p.expires_at IS NULL OR p.expires_at > NOW())
                AND EXISTS (SELECT 1 FROM user_semantic WHERE embedding_haves IS NOT NULL OR embedding_needs IS NOT NULL)
                ${
            if (latitude != null && longitude != null && radiusMeters != null) {
                "AND ST_DWithin(up.location::geography, ST_MakePoint(?, ?)::geography, ?)"
            } else {
                ""
            }
        }
            ORDER BY
                match_score DESC,
                distance_meters ASC NULLS LAST
            LIMIT ?;
        """.trimIndent()

        val queryParams = mutableListOf<Pair<IColumnType<*>, Any?>>()
        queryParams.add(VarCharColumnType() to userId)

        if (latitude != null && longitude != null) {
            queryParams.add(DoubleColumnType() to longitude)
            queryParams.add(DoubleColumnType() to latitude)
        }

        queryParams.add(VarCharColumnType() to userId)

        if (latitude != null && longitude != null && radiusMeters != null) {
            queryParams.add(DoubleColumnType() to longitude)
            queryParams.add(DoubleColumnType() to latitude)
            queryParams.add(DoubleColumnType() to radiusMeters)
        }

        queryParams.add(IntegerColumnType() to limit)

        val postings = mutableListOf<UserPostingWithDistance>()
        val postingAttributes = mutableMapOf<String, MutableList<PostingAttributeDto>>()

        TransactionManager.current().connection.prepareStatement(matchingQuery, false)
            .also { statement ->
                queryParams.forEachIndexed { index, (_, value) ->
                    statement[index + 1] = value.toString()
                }

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val postingId = rs.getString("id")
                    val matchScore = rs.getDouble("match_score")
                    val distanceMeters = rs.getDouble("distance_meters")

                    if (matchScore < 0.5) continue

                    val posting = UserPosting(
                        id = postingId,
                        userId = rs.getString("user_id"),
                        title = rs.getString("title"),
                        description = rs.getString("description"),
                        value = rs.getBigDecimal("value")?.toDouble(),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                        imageUrls = parseImageUrls(rs.getString("image_urls")),
                        isOffer = rs.getBoolean("is_offer"),
                        status = PostingStatus.valueOf(rs.getString("status").uppercase()),
                        attributes = emptyList(),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        updatedAt = rs.getTimestamp("updated_at").toInstant()
                    )

                    postings.add(
                        UserPostingWithDistance(
                            posting = posting,
                            distanceKm = if (distanceMeters != 0.0) distanceMeters / 1000 else null,
                            matchRelevancyScore = matchScore
                        )
                    )
                    postingAttributes[postingId] = mutableListOf()
                }
            }

        // Fetch attributes for all postings
        if (postings.isNotEmpty()) {
            val postingIds = postings.map { it.posting.id }
            PostingAttributesLinkTable
                .selectAll()
                .where { PostingAttributesLinkTable.postingId inList postingIds }
                .forEach { row ->
                    val postingId = row[PostingAttributesLinkTable.postingId]
                    postingAttributes[postingId]?.add(
                        PostingAttributeDto(
                            attributeId = row[PostingAttributesLinkTable.attributeId],
                            relevancy = row[PostingAttributesLinkTable.relevancy].toDouble()
                        )
                    )
                }
        }

        postings.map { postingWithDistance ->
            postingWithDistance.copy(
                posting = postingWithDistance.posting.copy(
                    attributes = postingAttributes[postingWithDistance.posting.id] ?: emptyList()
                )
            )
        }
    }

    override suspend fun updatePostingEmbedding(postingId: String): Boolean = dbQuery {
        try {
            log.debug("updatePostingEmbedding: Starting for postingId={}", postingId)
            
            val posting = UserPostingsTable
                .select(UserPostingsTable.title, UserPostingsTable.description)
                .where { UserPostingsTable.id eq postingId }
                .singleOrNull()
            
            if (posting == null) {
                log.error("updatePostingEmbedding: Posting not found with ID={}", postingId)
                return@dbQuery false
            }

            val title = posting[UserPostingsTable.title]
            val description = posting[UserPostingsTable.description]
            val combinedText = "$title. $description"
            
            log.debug("updatePostingEmbedding: Found posting. Combined text length: {}", combinedText.length)
            log.debug("updatePostingEmbedding: Using model={}, host={}", AiConfig.embedModel, AiConfig.ollamaHost)

            val embeddingQuery = """
                UPDATE user_postings
                SET embedding = ai.ollama_embed('${AiConfig.embedModel}', ?, host => '${AiConfig.ollamaHost}')
                WHERE id = ?;
            """.trimIndent()

            val rowsUpdated = TransactionManager.current().connection.prepareStatement(embeddingQuery, false)
                .also { statement ->
                    statement[1] = combinedText
                    statement[2] = postingId
                    statement.executeUpdate()
                }

            log.info("updatePostingEmbedding: Successfully generated embedding for postingId={} (rows updated: {})", postingId, rowsUpdated)
            true
        } catch (e: Exception) {
            log.error("updatePostingEmbedding: Error generating embedding for postingId={}: {}", postingId, e.message)
            // Check for Ollama connection errors specifically
            if (e.message?.contains("Connection refused") == true ||
                e.message?.contains("ConnectError") == true) {
                log.error("Ollama connection failed. Please ensure Ollama is running at ${AiConfig.ollamaHost}")
            } else {
                e.printStackTrace()
            }
            false
        }
    }

    override suspend fun markExpiredPostings(): Int = dbQuery {
        UserPostingsTable.update({
            (UserPostingsTable.expiresAt.isNotNull()) and
                    (UserPostingsTable.expiresAt lessEq Instant.now()) and
                    (UserPostingsTable.status eq "active")
        }) {
            it[status] = PostingStatus.EXPIRED.name.lowercase()
        }
    }
    
    override suspend fun hardDeleteExpiredPostings(gracePeriodDays: Int): Int {
        val cutoffDate = Instant.now().minusSeconds(gracePeriodDays.toLong() * 24 * 60 * 60)
        
        // First, fetch all postings that should be deleted (with their image URLs)
        val postingsToDelete = dbQuery {
            UserPostingsTable
                .selectAll()
                .where {
                    ((UserPostingsTable.status eq PostingStatus.EXPIRED.name.lowercase()) or
                     (UserPostingsTable.status eq PostingStatus.DELETED.name.lowercase())) and
                    (UserPostingsTable.updatedAt less cutoffDate)
                }
                .map { row ->
                    row[UserPostingsTable.id] to row[UserPostingsTable.imageUrls]
                }
        }
        
        if (postingsToDelete.isEmpty()) {
            log.debug("No postings to hard delete")
            return 0
        }
        
        log.info("Hard deleting ${postingsToDelete.size} postings expired for more than $gracePeriodDays days")
        
        var deletedCount = 0
        
        // Delete each posting with its associated data
        for ((postingId, imageUrls) in postingsToDelete) {
            try {
                // Delete images from storage
                if (imageUrls.isNotEmpty()) {
                    try {
                        imageStorage.deleteImages(imageUrls)
                        log.debug("Deleted ${imageUrls.size} images for posting $postingId")
                    } catch (e: Exception) {
                        log.error("Failed to delete images for posting $postingId", e)
                        // Continue with posting deletion even if image deletion fails
                    }
                }
                
                // Delete posting and its associated data in a transaction
                dbQuery {
                    // Delete attribute links first (foreign key constraint)
                    PostingAttributesLinkTable.deleteWhere { 
                        PostingAttributesLinkTable.postingId eq postingId 
                    }
                    
                    // Delete the posting itself
                    val deleted = UserPostingsTable.deleteWhere { 
                        UserPostingsTable.id eq postingId 
                    }
                    
                    if (deleted > 0) {
                        deletedCount++
                        log.debug("Hard deleted posting $postingId")
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to hard delete posting $postingId", e)
                // Continue with other postings even if one fails
            }
        }
        
        log.info("Successfully hard deleted $deletedCount postings")
        return deletedCount
    }

    private fun toUserPosting(postingRow: ResultRow, attributeRows: List<ResultRow>): UserPosting {
        return try {
            val attributes = attributeRows.mapNotNull { row ->
                // Handle LEFT JOIN - attribute fields may be NULL if no attributes exist
                val attrId = row.getOrNull(PostingAttributesLinkTable.attributeId)
                val relevancy = row.getOrNull(PostingAttributesLinkTable.relevancy)

                if (attrId != null && relevancy != null) {
                    PostingAttributeDto(
                        attributeId = attrId,
                        relevancy = relevancy.toDouble()
                    )
                } else {
                    null
                }
            }

            UserPosting(
                id = postingRow[UserPostingsTable.id],
                userId = postingRow[UserPostingsTable.userId],
                title = postingRow[UserPostingsTable.title],
                description = postingRow[UserPostingsTable.description],
                value = postingRow.getOrNull(UserPostingsTable.value)?.toDouble(),
                expiresAt = postingRow.getOrNull(UserPostingsTable.expiresAt),
                imageUrls = postingRow[UserPostingsTable.imageUrls],
                isOffer = postingRow[UserPostingsTable.isOffer],
                status = PostingStatus.valueOf(postingRow[UserPostingsTable.status].uppercase()),
                attributes = attributes,
                createdAt = postingRow[UserPostingsTable.createdAt],
                updatedAt = postingRow[UserPostingsTable.updatedAt]
            )
        } catch (e: Exception) {
            log.error("Error in toUserPosting - postingRow: {}, attributeRows: {}", postingRow, attributeRows, e)
            throw e
        }
    }

    private fun parseImageUrls(jsonString: String?): List<String> {
        if (jsonString.isNullOrBlank()) return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
