package app.bartering.features.attributes.dao

import org.slf4j.LoggerFactory
import app.bartering.config.AiConfig
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.extensions.normalizeAttributeForDBProcessing
import app.bartering.features.ai.AttributeCategorizer
import app.bartering.features.attributes.db.AttributesMasterTable
import app.bartering.features.attributes.model.Attribute
import app.bartering.features.attributes.model.AttributeSuggestion
import app.bartering.features.attributes.model.CategoryLink
import app.bartering.features.categories.AttributeCategoriesLinkTable
import app.bartering.utils.SecurityUtils
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import kotlin.math.abs

class AttributesDaoImpl : AttributesDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    val attributeCategorizer = AttributeCategorizer()

    // Helper function to map a database row to our data class.
    private fun toAttribute(row: ResultRow): Attribute = Attribute(
        id = row[AttributesMasterTable.id].value,
        attributeNameKey = row[AttributesMasterTable.attributeNameKey],
        localizationKey = row[AttributesMasterTable.localizationKey],
        customUserAttrText = row[AttributesMasterTable.customUserAttrText],
        isApproved = row[AttributesMasterTable.isApproved],

        // The 'embedding' column is of type Vector, which we can cast to a FloatArray.
        embedding = row[AttributesMasterTable.embedding]?.toArray()?.toList(),
        createdAt = row[AttributesMasterTable.createdAt],
        updatedAt = row[AttributesMasterTable.updatedAt],
    )

    override suspend fun populateMissingEmbeddings() {
        // First, find which of the provided keywords actually have NULL embeddings
        val keysWithNullEmbeddings = dbQuery {
            AttributesMasterTable
                .select(AttributesMasterTable.customUserAttrText)
                .where {
                    AttributesMasterTable.embedding.isNull()
                }
                .map { it[AttributesMasterTable.customUserAttrText] }
        }

        if (keysWithNullEmbeddings.isEmpty()) {
            return
        }

        log.info("Found {} attributes with missing embeddings. Populating now...", keysWithNullEmbeddings.size)

        // For each key, run the same UPDATE logic you use in createAttributeWithCategories
        dbQuery {
            for (key in keysWithNullEmbeddings) {
                // Validate key length
                if (key.length > 1000) {
                    log.warn("Skipping key with excessive length: {}...", key.take(50))
                    continue
                }
                
                // We use the key as the custom text for embedding generation
                val embeddingSql = """
                    UPDATE attributes
                    SET embedding = ai.ollama_embed(
                        '${AiConfig.embedModel}',
                        ?,
                        host => '${AiConfig.ollamaHost}'
                    )
                    WHERE attribute_key = ?
                """.trimIndent()

                TransactionManager.current().connection.prepareStatement(embeddingSql, false)
                    .also { statement ->
                        statement[1] = key
                        statement[2] = key
                        statement.executeUpdate()
                    }
            }
        }
        log.info("Finished populating embeddings for {} attributes", keysWithNullEmbeddings.size)
    }

    suspend fun createAttributeWithCategories(
        attributeKey: String,
        localizationKey: String,
        categoryLinks: List<CategoryLink>,
        customUserAttrText: String
    ): Attribute? {
        return dbQuery {
            // Step 1: Insert the main attribute. If it already exists, do nothing and return null for this flow.
            val newAttributeId = AttributesMasterTable.insertIgnoreAndGetId {
                it[AttributesMasterTable.attributeNameKey] = attributeKey
                it[AttributesMasterTable.localizationKey] = localizationKey
                it[AttributesMasterTable.customUserAttrText] = customUserAttrText
                it[isApproved] = true // New user-generated attributes require approval.
            }?.value ?: return@dbQuery null // Exit if the attribute already existed.

            // Step 2: Use pgai to generate and save the embedding for the new attribute.
            // Validate input length
            if (customUserAttrText.length > 10000) {
                log.warn("Custom attribute text too long: {}...", customUserAttrText.take(50))
                return@dbQuery null
            }

            val embeddingSql = """
                UPDATE attributes
                SET embedding = ai.ollama_embed(
                    '${AiConfig.embedModel}',
                    ?,
                    host => '${AiConfig.ollamaHost}'
                )
                WHERE id = ?
            """.trimIndent()

            // Use parameterized query for safety
            TransactionManager.current().connection.prepareStatement(embeddingSql, false)
                .also { statement ->
                    statement[1] = customUserAttrText
                    statement[2] = newAttributeId
                    statement.executeUpdate()
                }

            // Step 3: Link the new attribute to its categories.
            categoryLinks.forEach { link ->
                AttributeCategoriesLinkTable.insertIgnore {
                    it[attributeId] = newAttributeId
                    it[categoryId] = link.categoryId
                    it[relevancy] = link.relevancy
                }
            }

            // Step 4: Fetch the complete attribute record (including the new embedding) to return.
            AttributesMasterTable
                .selectAll().where { AttributesMasterTable.id eq newAttributeId }
                .map(::toAttribute)
                .first()
        }
    }

    override suspend fun findComplementaryInterests(
        havesKeywords: Map<String, Double>,
        userId: String,
        limit: Int
    ): List<AttributeSuggestion> {
        if (havesKeywords.isEmpty()) {
            return emptyList()
        }

        // The main query that finds matching interests with category UI hint.
        val finalQuery = """
        WITH profile_vector AS (
            SELECT embedding_profile AS embedding 
                 FROM user_semantic_profiles 
                 WHERE user_id = ?
        )
        SELECT
            a.attribute_key,
            a.embedding <=> (SELECT embedding FROM profile_vector) AS similarity,
            c.ui_style_hint
        FROM
            attributes a
        CROSS JOIN profile_vector pv
        LEFT JOIN attribute_categories_link acl ON a.id = acl.attribute_id
        LEFT JOIN categories c ON acl.category_id = c.id
        WHERE
            a.embedding IS NOT NULL
            
        ORDER BY
            similarity DESC
        LIMIT ?;
    """.trimIndent()
        //AND a.attribute_key NOT IN (${profileKeywords.keys.joinToString { "'$it'" }})

        val results = mutableListOf<AttributeSuggestion>()

        dbQuery {
            TransactionManager.current().connection.prepareStatement(finalQuery, false)
                .also { statement ->
                    // Set the parameter for the LIMIT clause
                    statement[1] = userId
                    statement[2] = limit
                    val rs = statement.executeQuery()
                    while (rs.next()) {
                        val key = rs.getString("attribute_key")
                        val similarity = rs.getDouble("similarity")
                        val uiStyleHint = rs.getString("ui_style_hint")
                        results.add(AttributeSuggestion(key, similarity, uiStyleHint))
                    }
                }
        }
        log.debug("findComplementaryInterests result: {} items", results.size)
        return results
    }

    /**
     * Helper function to build the SQL for the "Haves" profile vector.
     * Uses normalized relative weighting to ensure high-relevancy attributes dominate.
     *
     * WEIGHTING STRATEGY:
     * - Same as buildProfileVectorSql but for pre-existing attribute embeddings
     * - High weights (>0.7) get boosted so "Plumbing" skill strongly matches "Sink fixing"
     * - Uses sqrt curve to soften extreme differences
     */
    internal fun buildHavesProfileVectorSql(havesKeywords: Map<String, Double>): String {
        val vectorParts = mutableListOf<String>()
        val zeroVector = "ARRAY_FILL(0, ARRAY[1024])::vector"

        fun getEmbeddingSql(key: String): String {
            // Validate key to prevent SQL injection
            require(key.length < 500) { "Attribute key too long: ${key.take(50)}..." }
            require(!SecurityUtils.containsSqlInjectionPatterns(key)) {
                "Attribute key contains dangerous patterns: ${key.take(50)}..."
            }

            val sanitizedKey = SecurityUtils.sanitizeSqlString(key)
            return "COALESCE((SELECT embedding FROM attributes WHERE attribute_key = '$sanitizedKey'), $zeroVector)"
        }

        // Filter to positive weights only
        val positiveKeywords = havesKeywords.filter { it.value > 0 }

        if (positiveKeywords.isEmpty()) {
            return "SELECT $zeroVector AS embedding"
        }

        // Calculate normalized weights with emphasis on high-relevancy attributes
        val maxWeight = positiveKeywords.values.maxOrNull() ?: 1.0
        val minWeight = positiveKeywords.values.minOrNull() ?: 0.0
        val weightRange = maxWeight - minWeight

        for ((keyword, originalWeight) in positiveKeywords) {
            // Normalize to [0, 1] range based on min/max
            val normalizedWeight = if (weightRange > 0) {
                (originalWeight - minWeight) / weightRange
            } else {
                1.0 // All weights are the same
            }

            // Apply softer curve (sqrt) to avoid quadratic extremes
            val curvedWeight = kotlin.math.sqrt(normalizedWeight)

            // Boost high-relevancy attributes (>0.7 original weight)
            val finalWeight = if (originalWeight > 0.7) {
                // High-priority: blend 70% curved + 30% boosted
                0.7 * curvedWeight + 0.3 * 1.0
            } else if (originalWeight > 0.5) {
                // Medium-priority: use curved weight as-is
                curvedWeight
            } else {
                // Low-priority: reduce influence slightly
                0.7 * curvedWeight
            }

            val weightSql = "scalar_mult(${getEmbeddingSql(keyword)}, ${finalWeight}::real)"
            vectorParts.add(weightSql)
        }

        if (vectorParts.isEmpty()) {
            return "SELECT $zeroVector AS embedding"
        }

        val combinedVectorSql = vectorParts.joinToString(" + ")
        val totalWeight = positiveKeywords.values.sumOf { it }

        val divisor = if (totalWeight > 0) totalWeight else 1.0
        val finalScalar = 1.0 / divisor

        return "SELECT scalar_mult((${combinedVectorSql}), ${finalScalar}::real) AS embedding"
    }

    override suspend fun findOrCreate(attributeNameKey: String):
            Attribute? {

        return dbQuery {

            val normalizedAttr = attributeNameKey.normalizeAttributeForDBProcessing()
            // 1. Try to find the attribute by its key.
            val existingAttribute = AttributesMasterTable
                .selectAll().where { AttributesMasterTable.attributeNameKey eq normalizedAttr }
                .orWhere { AttributesMasterTable.customUserAttrText eq attributeNameKey }
                .map(::toAttribute)
                .singleOrNull()

            if (existingAttribute != null) {
                log.debug("Attribute already exists: {}", attributeNameKey)
                return@dbQuery existingAttribute
            }
            val originalCustomUserText = attributeNameKey

            var mostRelevantCategoryLinks =
                attributeCategorizer.findBestCategory(originalCustomUserText)

            if (mostRelevantCategoryLinks.toList().isEmpty()) {
                // Misc/Non-relevant/Unknow Category
                mostRelevantCategoryLinks = listOf(CategoryLink(8, 1.0.toBigDecimal()))
            }
            // 2. If it doesn't exist, call the creation logic.
            val localizationKey = "attr_$normalizedAttr"
            val newAttribute = createAttributeWithCategories(
                normalizedAttr,
                localizationKey,
                mostRelevantCategoryLinks,
                originalCustomUserText
            )

            // 3. If creation returned null (race condition), try to fetch it again
            if (newAttribute == null) {
                log.warn("Attribute creation returned null for '{}', checking if it exists now", attributeNameKey)
                val retryFetch = AttributesMasterTable
                    .selectAll().where { AttributesMasterTable.attributeNameKey eq normalizedAttr }
                    .orWhere { AttributesMasterTable.customUserAttrText eq attributeNameKey }
                    .map(::toAttribute)
                    .singleOrNull()

                if (retryFetch != null) {
                    log.info("Found attribute on retry: {}", attributeNameKey)
                    return@dbQuery retryFetch
                } else {
                    log.error("Failed to create or find attribute: {}", attributeNameKey)
                    return@dbQuery null
                }
            }

            return@dbQuery newAttribute
        }
    }

    override suspend fun findSimilarInterestsForProfile(
        profileKeywords: Map<String, Double>,
        limit: Int,
        userId: String
    ): List<AttributeSuggestion> {

        // The main query that finds matching interests with category UI hint.
        val finalQuery = """
        WITH profile_vector AS (
            SELECT embedding_profile AS embedding 
                 FROM user_semantic_profiles 
                 WHERE user_id = ?
        ), needs_vector AS (
            SELECT embedding_needs AS embedding 
                 FROM user_semantic_profiles 
                 WHERE user_id = ?
        )
        SELECT
            a.attribute_key,
            ((0.6 * (1 - (a.embedding <=> (SELECT embedding FROM profile_vector))) + 
            (0.4 * ((SELECT embedding FROM needs_vector) <=> a.embedding)))) AS similarity,
            c.ui_style_hint
        FROM
            attributes a
        CROSS JOIN profile_vector pv
        CROSS JOIN needs_vector nv
        LEFT JOIN attribute_categories_link acl ON a.id = acl.attribute_id
        LEFT JOIN categories c ON acl.category_id = c.id
        WHERE
            a.embedding IS NOT NULL
        ORDER BY
            similarity DESC
        LIMIT ?;
    """.trimIndent()

        val results = mutableListOf<AttributeSuggestion>()

        dbQuery {
            TransactionManager.current().connection.prepareStatement(finalQuery, false)
                .also { statement ->
                    // Set the parameter for the LIMIT clause
                    statement[1] = userId
                    statement[2] = userId
                    statement[3] = limit
                    val rs = statement.executeQuery()
                    while (rs.next()) {
                        val key = rs.getString("attribute_key")
                        val similarity = rs.getDouble("similarity")
                        val uiStyleHint = rs.getString("ui_style_hint")
                        results.add(AttributeSuggestion(key, similarity, uiStyleHint))
                    }
                }
        }
        log.debug("findSimilarInterestsForProfile result: {} items", results.size)
        return results
    }

    /**
     * Helper function to dynamically build the SQL for the profile vector calculation.
     * This version uses the custom 'scalar_mult' SQL function and simple subqueries.
     *
     * WEIGHTING STRATEGY:
     * - Normalizes weights relative to the min/max range in the profile
     * - Applies a softer curve (sqrt) to avoid extreme quadratic effects
     * - High weights (>0.7) get boosted to ensure dominant interests shine through
     * - This helps "Sink fixing" match users with high "Plumbing" relevancy
     */
    internal fun buildProfileVectorSql(profileKeywords: Map<String, Double>): String {
        val vectorParts = mutableListOf<String>()

        // A helper function to generate the SQL for an on-the-fly embedding.
        fun getEmbeddingSql(text: String): String {
            // Validate input to prevent SQL injection
            require(text.length < 10000) { "Text too long for embedding: ${text.take(50)}..." }
            require(!SecurityUtils.containsSqlInjectionPatterns(text)) {
                "Text contains potentially dangerous SQL patterns: ${text.take(50)}..."
            }

            // Use robust sanitization as defense-in-depth
            val sanitized = SecurityUtils.sanitizeSqlString(text)

            // Use pgai to generate the embedding directly in the query.
            return "ai.ollama_embed('${AiConfig.embedModel}', '$sanitized', host => '${AiConfig.ollamaHost}')"
        }

        if (profileKeywords.isEmpty()) {
            return "SELECT NULL::vector AS embedding"
        }

        // Calculate normalized weights with emphasis on high-relevancy keywords
        val maxWeight = profileKeywords.values.maxOrNull() ?: 1.0
        val minWeight = profileKeywords.values.minOrNull() ?: 0.0
        val weightRange = maxWeight - minWeight

        log.trace("Build Profile Vector: max={}, min={}", maxWeight, minWeight)

        for ((keyword, originalWeight) in profileKeywords) {
            // Strategy 1: Normalize to [0, 1] range based on min/max
            val normalizedWeight = if (weightRange > 0) {
                (originalWeight - minWeight) / weightRange
            } else {
                1.0 // All weights are the same, treat equally
            }

            // Strategy 2: Apply a softer curve to avoid extreme quadratic effects
            // sqrt gives: 0.81 -> 0.90, 0.49 -> 0.70, 0.16 -> 0.40
            val curvedWeight = kotlin.math.sqrt(normalizedWeight)

            // Strategy 3: Boost high-relevancy keywords (>0.7 original weight)
            // These are the user's main interests and should dominate matching
            val finalWeight = if (originalWeight > 0.7) {
                // High-priority: blend 70% curved + 30% boosted
                0.7 * curvedWeight + 0.3 * 1.0
            } else if (originalWeight > 0.5) {
                // Medium-priority: use curved weight as-is
                curvedWeight
            } else {
                // Low-priority: reduce influence slightly
                0.7 * curvedWeight
            }

            // Apply the final weight to the embedding
            val weightSql = "scalar_mult(${getEmbeddingSql(keyword)}, ${finalWeight}::real)"
            vectorParts.add(weightSql)
        }

        if (vectorParts.isEmpty()) {
            return "SELECT NULL::vector AS embedding"
        }

        val combinedVectorSql = vectorParts.joinToString(" + ")

        // Calculate total weight for normalization (use original weights)
        val totalWeight = profileKeywords.values.sumOf { abs(it) }

        // Division is multiplication by the inverse.
        val divisor = if (totalWeight > 0) totalWeight else 1.0
        val finalScalar = 1.0 / divisor

        // Use the custom function for the final "division" (averaging) step.
        return "SELECT scalar_mult((${combinedVectorSql}), ${finalScalar}::real) AS embedding"
    }

}