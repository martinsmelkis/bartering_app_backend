package org.barter.features.profile.dao

import org.barter.config.AiConfig
import org.barter.extensions.DatabaseFactory.dbQuery
import org.barter.features.attributes.db.UserAttributesTable
import org.barter.features.authentication.model.UserRegistrationDataDto
import org.barter.features.profile.cache.SearchEmbeddingCache
import org.barter.features.profile.model.*
import org.jetbrains.exposed.sql.*
import kotlinx.serialization.json.Json
import net.postgis.jdbc.geometry.Point
import org.barter.features.attributes.dao.AttributesDaoImpl
import org.barter.features.attributes.model.UserAttributeType
import org.barter.features.profile.db.UserRegistrationDataTable
import org.barter.features.profile.db.UserSemanticProfilesTable
import org.barter.utils.HashUtils
import org.barter.utils.SecurityUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.koin.java.KoinJavaComponent.inject
import java.sql.ResultSet
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.getValue
import kotlin.text.split
import kotlin.text.substring
import kotlin.text.toDouble
import kotlin.text.trim

class UserProfileDaoImpl : UserProfileDao {

    // Embedding cache for frequent searches - stores up to 1000 embeddings, 24 h expiry
    private val embeddingCache = SearchEmbeddingCache(
        maxSize = 1000,
        ttlMinutes = 60
    )

    override suspend fun createProfile(user: UserRegistrationDataDto): Unit = dbQuery {
        UserRegistrationDataTable.insert {
            it[id] = user.id!!
            it[publicKey] = user.publicKey
        }
    }

    override suspend fun getProfile(userId: String): UserProfile? = dbQuery {
        // Validate userId to prevent SQL injection
        if (!SecurityUtils.isValidUUID(userId)) {
            println("Invalid userId format: $userId")
            return@dbQuery null
        }

        // First, fetch the basic profile info with location using raw SQL (to avoid Exposed's PostGIS issues)
        val profileQuery = """
            SELECT 
                up.user_id,
                up.name,
                up.location,
                up.profile_keywords_with_weights::text as profile_keywords_with_weights
            FROM user_profiles up
            WHERE up.user_id = ?
        """.trimIndent()

        var profileData: Pair<String, Pair<Double?, Double?>?>? = null
        var profileKeywords: Map<String, Double>? = null

        TransactionManager.current().connection.prepareStatement(profileQuery, false)
            .also { statement ->
                statement[1] = userId
                val rs = statement.executeQuery()
                if (rs.next()) {
                    val name = rs.getString("name") ?: ""
                    val keywordsJson = rs.getString("profile_keywords_with_weights")

                    profileKeywords = if (keywordsJson != null) {
                        try {
                            Json.decodeFromString<Map<String, Double>>(keywordsJson)
                        } catch (_: Exception) {
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }

                    // Parse location from string
                    val (longitude, latitude) = parseLocation(rs)

                    profileData = name to (longitude to latitude)
                } else {
                    return@dbQuery null
                }
            }

        val (name, locationPair) = profileData ?: return@dbQuery null
        val (longitude, latitude) = locationPair ?: (null to null)

        // Now fetch attributes using Exposed (this works fine)
        val attributes = mutableListOf<UserAttributeDto>()
        val attributeIds = mutableListOf<String>()

        UserAttributesTable
            .selectAll()
            .where { UserAttributesTable.userId eq userId }
            .forEach { row ->
                val attrId = row[UserAttributesTable.attributeId]
                attributeIds.add(attrId)
                attributes.add(
                    UserAttributeDto(
                        attributeId = attrId,
                        type = UserAttributeType.entries.indexOf(row[UserAttributesTable.type]),
                        relevancy = row[UserAttributesTable.relevancy].toDouble(),
                        description = row.getOrNull(UserAttributesTable.description),
                        uiStyleHint = null // Will be filled in next step
                    )
                )
            }

        // Fetch ui_style_hints for all attributes
        val uiStyleHints = fetchUiStyleHintsForAttributes(attributeIds)
        val attributesWithHints = attributes.map { attr ->
            attr.copy(uiStyleHint = uiStyleHints[attr.attributeId])
        }

        // Fetch active posting IDs for this user
        val activePostingIds = fetchActivePostingIds(userId)

        UserProfile(
            userId = userId,
            name = name,
            latitude = latitude,
            longitude = longitude,
            attributes = attributesWithHints,
            profileKeywordDataMap = profileKeywords,
            activePostingIds = activePostingIds
        )
    }

    override suspend fun getUserPublicKeyById(id: String): String? {
        val publicKey = dbQuery {
            return@dbQuery try {
                val selectRow = UserRegistrationDataTable.select(UserRegistrationDataTable.id,
                    UserRegistrationDataTable.publicKey)
                    .where(UserRegistrationDataTable.id eq id).first()
                selectRow[UserRegistrationDataTable.publicKey]
            } catch (e: NoSuchElementException) {
                e.printStackTrace()
                null
            }
        }
        return publicKey
    }

    override suspend fun updateProfile(userId: String, request: UserProfileUpdateRequest):
            String = dbQuery {

        // Check if this is a new user profile (doesn't exist yet)
        val existingProfile = UserProfilesTable
            .select(UserProfilesTable.userId, UserProfilesTable.name)
            .where { UserProfilesTable.userId eq userId }
            .singleOrNull()

        val isNewProfile = existingProfile == null
        val existingName = existingProfile?.getOrNull(UserProfilesTable.name)

        // Generate default username if needed (new user with no name provided)
        val finalName = when {
            !request.name.isNullOrBlank() -> request.name
            !existingName.isNullOrBlank() -> existingName
            isNewProfile -> {
                // Generate a default username based on current user count
                val userCount = UserProfilesTable.selectAll().count()
                "User_${userCount + 1}"
            }
            else -> existingName
        }

        UserProfilesTable.upsert { table ->
            table[UserProfilesTable.userId] = userId
            table[UserProfilesTable.name] = finalName
            if (request.latitude != null && request.longitude != null
                && request.latitude != 0.0 && request.longitude != 0.0) {
                // Create a geography point from the client's coordinates, setting the SRID to 4326
                // NOTE: PostGIS Point expects (longitude, latitude) NOT (latitude, longitude)
                table[location] = Point(request.longitude, request.latitude)
                    .also { p -> p.srid = 4326 }
            }
            request.profileKeywordDataMap?.let { map ->
                table[UserProfilesTable.profileKeywordDataMap] = request.profileKeywordDataMap
            }
        }

        request.attributes?.forEach { attr ->
            UserAttributesTable.insertIgnore {
                it[UserAttributesTable.userId] = userId
                it[attributeId] = attr.attributeId
                it[type] = UserAttributeType.entries[attr.type]
                it[relevancy] = attr.relevancy.toBigDecimal()
                attr.description?.let { d -> it[description] = d }
            }
        }

        request.attributes?.let {
            updateSemanticProfile(userId, UserAttributeType.PROVIDING)
            updateSemanticProfile(userId, UserAttributeType.SEEKING)
        }
        request.profileKeywordDataMap?.let {
            updateSemanticProfile(userId, UserAttributeType.PROFILE)
        }

        finalName ?: ""
    }

    /**
     * Efficiently finds nearby users using a PostGIS spatial index.
     *
     * @param latitude The latitude of the search center
     * @param longitude The longitude of the search center
     * @param radiusMeters The search radius in meters
     * @param excludeUserId Optional user ID to exclude from results (typically the requesting user)
     */
    override suspend fun getNearbyProfiles(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        excludeUserId: String?
    ): List<UserProfileWithDistance> = dbQuery {

        // Validate excludeUserId to prevent SQL injection
        if (excludeUserId != null && !SecurityUtils.isValidUUID(excludeUserId)) {
            println("Invalid excludeUserId format: $excludeUserId")
            return@dbQuery emptyList()
        }

        // Build the nearby profiles query using PostGIS spatial functions
        val nearbyQuery = """
            SELECT
                u.id as user_id,
                up.name,
                up.location as location,
                up.profile_keywords_with_weights::text as profile_keywords_with_weights,
                ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters
            FROM
                user_registration_data u
            INNER JOIN user_profiles up ON u.id = up.user_id
            WHERE
                -- Use ST_DWithin with geography for accurate distance on Earth's surface
                -- This uses the spatial GIST index for fast lookup
                ST_DWithin(up.location::geography, ST_MakePoint(?, ?)::geography, ?)
                ${if (excludeUserId != null) "AND u.id != ?" else ""}
            ORDER BY
                distance_meters ASC
            LIMIT 30;
        """.trimIndent()

        println("@@@@@@@@@@ Executing nearby profiles query at ($latitude, $longitude) radius: $radiusMeters")

        // Prepare query parameters
        val queryParams = mutableListOf<Pair<IColumnType<*>, Any?>>()
        // ST_MakePoint for distance calculation (lon, lat)
        queryParams.add(DoubleColumnType() to longitude)
        queryParams.add(DoubleColumnType() to latitude)
        // ST_MakePoint for ST_DWithin (lon, lat)
        queryParams.add(DoubleColumnType() to longitude)
        queryParams.add(DoubleColumnType() to latitude)
        // Radius in meters
        queryParams.add(DoubleColumnType() to radiusMeters)
        // Optional exclude user ID
        if (excludeUserId != null) {
            queryParams.add(VarCharColumnType() to excludeUserId)
        }

        val userProfiles = mutableListOf<UserProfileWithDistance>()
        val userAttributes = mutableMapOf<String, MutableList<UserAttributeDto>>()

        // Execute the nearby profiles query
        TransactionManager.current().connection.prepareStatement(nearbyQuery, false)
            .also { statement ->
                // Set all parameters at once with proper types
                statement.fillParameters(queryParams.mapIndexed { index, (columnType, value) ->
                    columnType to value
                })

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val foundUserId = rs.getString("user_id")
                    val keywordsJson = rs.getString("profile_keywords_with_weights")
                    val mappedKeyWords: Map<String, Double>? = if (keywordsJson != null) {
                        try {
                            Json.decodeFromString<Map<String, Double>>(keywordsJson)
                        } catch (_: Exception) {
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }

                    // Parse location from string since rs.getObject returns geography type
                    val (longitude, latitude) = parseLocation(rs)

                    val distanceMeters = rs.getDouble("distance_meters")

                    userProfiles.add(
                        UserProfileWithDistance(
                            profile = UserProfile(
                                userId = foundUserId,
                                name = rs.getString("name") ?: "",
                                latitude = latitude,
                                longitude = longitude,
                                attributes = emptyList(), // We'll populate this next
                                profileKeywordDataMap = mappedKeyWords
                            ),
                            distanceKm = distanceMeters / 1000,
                            matchRelevancyScore = 1.0
                        )
                    )
                    userAttributes[foundUserId] = mutableListOf()
                }
            }

        val results = fetchAttributesForProfiles(userProfiles, userAttributes)
        println("@@@@@@@@@@ Found ${results.size} nearby profiles")
        results
    }

    override suspend fun getSimilarProfiles(
        userId: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?
    ): List<UserProfileWithDistance> = dbQuery {
        findProfilesBySemanticSimilarity(userId, UserAttributeType.PROVIDING,
            UserAttributeType.PROVIDING, latitude, longitude, radiusMeters)
    }

    override suspend fun getHelpfulProfiles(
        userId: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?
    ): List<UserProfileWithDistance> = dbQuery {
        findProfilesBySemanticSimilarity(userId, UserAttributeType.SEEKING,
            UserAttributeType.PROVIDING, latitude, longitude, radiusMeters)
    }

    private suspend fun findProfilesBySemanticSimilarity(
        currentUserId: String,
        currentUserProfileType: UserAttributeType,
        otherUsersProfileType: UserAttributeType,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?
    ): List<UserProfileWithDistance> = dbQuery {
        // Validate userId to prevent SQL injection
        if (!SecurityUtils.isValidUUID(currentUserId)) {
            println("Invalid userId format: $currentUserId")
            return@dbQuery emptyList()
        }
        val myEmbeddingColumnType = when (currentUserProfileType) {
            UserAttributeType.SEEKING -> UserSemanticProfilesTable.embeddingNeeds
            UserAttributeType.PROVIDING -> UserSemanticProfilesTable.embeddingHaves
            UserAttributeType.SHARING -> UserSemanticProfilesTable.embeddingHaves
            UserAttributeType.PROFILE -> UserSemanticProfilesTable.embeddingProfile
        }

        val othersEmbeddingColumnType = when (otherUsersProfileType) {
            UserAttributeType.SEEKING -> UserSemanticProfilesTable.embeddingNeeds
            UserAttributeType.PROVIDING -> UserSemanticProfilesTable.embeddingHaves
            UserAttributeType.SHARING -> UserSemanticProfilesTable.embeddingHaves
            UserAttributeType.PROFILE -> UserSemanticProfilesTable.embeddingProfile
        }
        // This is the SQL that will do all the heavy lifting.
        // It's broken down into steps for clarity.
        val semanticSimilarityQuery = """
            -- Step 1: Define the current user's profile vector as a CTE (Common Table Expression).
            WITH current_user_profile AS (
                SELECT ${myEmbeddingColumnType.name}, embedding_profile
                FROM user_semantic_profiles
                WHERE user_id = ?
            )
            -- Step 2: Main SELECT statement to find and rank other users.
            SELECT
                -- We need the other user's ID and their profile data.
                other_user.id as user_id,
                other_user_profile.name,
                other_user_profile.location as location,
                other_user_profile.profile_keywords_with_weights::text as profile_keywords_with_weights,
                -- Calculate attribute similarity (e.g., my needs vs their haves)
                (1 - (other_user_semantic_profile.${othersEmbeddingColumnType.name} <=> (SELECT ${myEmbeddingColumnType.name} FROM current_user_profile))) as attribute_similarity,
                -- Calculate profile similarity (my personality vs their personality)
                (1 - (other_user_semantic_profile.embedding_profile <=> (SELECT embedding_profile FROM current_user_profile))) as profile_similarity
                ${if (latitude != null && longitude != null) {
                    // Conditionally add the GEOGRAPHIC DISTANCE if location is provided.
                    ", ST_Distance(other_user_profile.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters"
                } else {
                    ", NULL as distance_meters"
                }
        }
            FROM
                -- Find all other users...
                user_registration_data other_user
            -- ...who have a profile...
            JOIN user_profiles other_user_profile ON other_user.id = other_user_profile.user_id
            -- ...and who have a calculated semantic profile.
            JOIN user_semantic_profiles other_user_semantic_profile ON other_user.id = other_user_semantic_profile.user_id
            WHERE
                -- Exclude the current user from their own search results.
                other_user.id != ?
                -- Ensure the profiles we are comparing against are not NULL.
                AND other_user_semantic_profile.${othersEmbeddingColumnType.name} IS NOT NULL
                -- Make sure the current user's profile exists to avoid errors.
                AND EXISTS (SELECT 1 FROM current_user_profile WHERE ${myEmbeddingColumnType.name} IS NOT NULL)
                ${
                    // Conditionally add the GEOSPATIAL filter if location and radius are provided.
                    if (latitude != null && longitude != null && radiusMeters != null) {
                        // ST_DWithin is very fast because it uses the spatial index.
                        "AND ST_DWithin(other_user_profile.location::geography, ST_MakePoint(?, ?)::geography, ?)"
                    } else {
                        ""
                    }
                }
            -- Order results primarily by how semantically similar they are (higher is better).
            ORDER BY
                -- Weighted average: 70% attribute similarity, 30% profile similarity
                (0.7 * (1 - (other_user_semantic_profile.${othersEmbeddingColumnType.name} <=> (SELECT ${myEmbeddingColumnType.name} FROM current_user_profile)))) +
                ${
                    if ((currentUserProfileType == UserAttributeType.SEEKING 
                                && otherUsersProfileType == UserAttributeType.PROVIDING) ||
                        (currentUserProfileType == UserAttributeType.PROVIDING 
                                && otherUsersProfileType == UserAttributeType.SEEKING)) {
                        "(0.3 * (other_user_semantic_profile.embedding_profile <=> (SELECT embedding_profile FROM current_user_profile)))"
                    } else {
                        "(0.3 * (1 - (other_user_semantic_profile.embedding_profile <=> (SELECT embedding_profile FROM current_user_profile))))"
                    }
                }
                DESC,
                distance_meters ASC NULLS LAST
            LIMIT 20;
        """.trimIndent()

        //println("@@@@@@@@@@@@ Executing Similar Profiles Query:\n$semanticSimilarityQuery")

        // This list will hold the parameters for the prepared statement in the correct order.
        val queryParams = mutableListOf<Pair<IColumnType<*>, Any?>>()
        // First parameter: currentUserId for the CTE
        queryParams.add(VarCharColumnType() to currentUserId)
        if (latitude != null && longitude != null) {
            queryParams.add(DoubleColumnType() to longitude) // ST_MakePoint is (lon, lat)
            queryParams.add(DoubleColumnType() to latitude)
        }
        // Second parameter: currentUserId for the WHERE clause
        queryParams.add(VarCharColumnType() to currentUserId)
        if (latitude != null && longitude != null && radiusMeters != null) {
            queryParams.add(DoubleColumnType() to longitude)
            queryParams.add(DoubleColumnType() to latitude)
            queryParams.add(DoubleColumnType() to radiusMeters)
        }

        val userProfiles = mutableListOf<UserProfileWithDistance>()
        val userAttributes = mutableMapOf<String, MutableList<UserAttributeDto>>()

        // Execute the main user search query
        TransactionManager.current().connection.prepareStatement(semanticSimilarityQuery, false).also { statement ->
            queryParams.forEachIndexed { index, (_, value) ->
                statement[index + 1] = value.toString()
            }
            val rs = statement.executeQuery()
            while (rs.next()) {
                val foundUserId = rs.getString("user_id")
                val keywordsJson = rs.getString("profile_keywords_with_weights")
                val mappedKeyWords: Map<String, Double>? = if (keywordsJson != null) {
                    try {
                        Json.decodeFromString<Map<String, Double>>(keywordsJson)
                    } catch (_: Exception) {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
                val attrSimilarityScore = rs.getDouble("attribute_similarity")
                val profileSimilarityScore = rs.getDouble("profile_similarity")

                if (attrSimilarityScore < 0.7 && profileSimilarityScore < 0.6
                    && userProfiles.size > 10
                ) continue

                // Parse location from string since rs.getObject returns geography type
                val (longitude, latitude) = parseLocation(rs)

                userProfiles.add(
                    UserProfileWithDistance(
                        profile = UserProfile(
                            userId = foundUserId,
                            name = rs.getString("name") ?: "",
                            latitude = latitude,
                            longitude = longitude,
                            attributes = emptyList(), // We'll populate this next
                            profileKeywordDataMap = mappedKeyWords
                        ),
                        distanceKm = rs.getDouble("distance_meters") / 1000,
                        matchRelevancyScore = profileSimilarityScore
                    )
                )
                userAttributes[foundUserId] = mutableListOf()
            }
        }

        val results = fetchAttributesForProfiles(userProfiles, userAttributes)
        //println("@@@@@@@@@ Found similar profiles: $results")
        results
    }

    /**
     * Calculates and stores a user's semantic profile embedding.
     * This should be called whenever a user's attributes change.
     */
    override suspend fun updateSemanticProfile(userId: String, attributeType: UserAttributeType) =
        dbQuery {
            // Validate userId to prevent SQL injection
            if (!SecurityUtils.isValidUUID(userId)) {
                println("Invalid userId format in updateSemanticProfile: $userId")
                return@dbQuery
            }

            val resultMap = hashMapOf<String, Double>()

            if (attributeType != UserAttributeType.PROFILE) {

                val semanticProfileHashes = UserSemanticProfilesTable
                    .select(UserSemanticProfilesTable.hashNeeds,
                        UserSemanticProfilesTable.hashHaves)
                    .where { UserSemanticProfilesTable.userId eq userId }
                    .firstOrNull()

                val hashCol = if (attributeType == UserAttributeType.SEEKING)
                    UserSemanticProfilesTable.hashNeeds else UserSemanticProfilesTable.hashHaves

                // 1. Fetch all of the user's attributes of a specific type (e.g., all 'PROVIDING' attributes).
                val userAttributes = UserAttributesTable
                    .selectAll()
                    .where { (UserAttributesTable.userId eq userId) and (UserAttributesTable.type eq attributeType) }

                userAttributes.forEach {
                    resultMap[it[UserAttributesTable.attributeId]] = it[UserAttributesTable.relevancy].toDouble()
                }

                val hash = if (resultMap.isNotEmpty()) HashUtils.sha256(resultMap) else null
                if (hash != null && hash == (semanticProfileHashes?.get(hashCol) ?: "")) {
                    println("@@@@@@@@@@ No changes to semantic haves/needs $hashCol for user $userId")
                    return@dbQuery
                }
                UserSemanticProfilesTable.update({ UserSemanticProfilesTable.userId eq userId }) {
                    it[hashCol] = hash
                }
            }

            val attributesDao: AttributesDaoImpl by inject(AttributesDaoImpl::class.java)

            val myEmbeddingColumnType = when (attributeType) {
                UserAttributeType.SEEKING -> UserSemanticProfilesTable.embeddingNeeds
                UserAttributeType.PROVIDING -> UserSemanticProfilesTable.embeddingHaves
                UserAttributeType.SHARING -> UserSemanticProfilesTable.embeddingHaves
                UserAttributeType.PROFILE -> UserSemanticProfilesTable.embeddingProfile
            }

            if (attributeType == UserAttributeType.SEEKING
                || attributeType == UserAttributeType.PROVIDING
                || attributeType == UserAttributeType.SHARING) {

                // 2. Reuse the brilliant SQL-building logic from AttributesDaoImpl to create the profile vector.
                // We pass the user's attributes and relevancy scores directly.
                val profileVectorSql = attributesDao.buildHavesProfileVectorSql(resultMap)

                // 3. Construct the final UPSERT query to save the calculated vector.
                val finalUpsertQuery = """
                INSERT INTO user_semantic_profiles (user_id, ${myEmbeddingColumnType.name})
                VALUES (
                    ?,
                    ($profileVectorSql) -- Execute the vector calculation as a subquery
                )
                ON CONFLICT (user_id)
                DO UPDATE SET 
                    ${myEmbeddingColumnType.name} = EXCLUDED.${myEmbeddingColumnType.name},
                    updated_at = CURRENT_TIMESTAMP;
            """.trimIndent()

                TransactionManager.current().connection.prepareStatement(finalUpsertQuery, false)
                    .also { statement ->
                        statement[1] = userId
                        statement.executeUpdate() }
            } else {
                val userProfile = UserProfilesTable
                    .select(UserProfilesTable.profileKeywordDataMap)
                    .where { UserProfilesTable.userId eq userId }
                    .firstOrNull() ?: return@dbQuery

                val keywords = userProfile[UserProfilesTable.profileKeywordDataMap]
                if (keywords.isNullOrEmpty()) {
                    UserSemanticProfilesTable.update({ UserSemanticProfilesTable.userId eq userId }) {
                        it[embeddingProfile] = null
                    }
                    return@dbQuery
                }

                val semanticProfileHashes = UserSemanticProfilesTable
                    .select(UserSemanticProfilesTable.hashProfile)
                    .where { UserSemanticProfilesTable.userId eq userId }
                    .firstOrNull()

                val hash = if (keywords.isNotEmpty()) HashUtils.sha256(keywords) else null
                if (hash != null && hash == (semanticProfileHashes?.get(UserSemanticProfilesTable.hashProfile) ?: "")) {
                    println("@@@@@@@@@@ No changes to semantic profile ${UserSemanticProfilesTable.hashProfile} for user $userId")
                    return@dbQuery
                }
                UserSemanticProfilesTable.update({ UserSemanticProfilesTable.userId eq userId }) {
                    it[UserSemanticProfilesTable.hashProfile] = hash
                }

                val vectorSql = attributesDao.buildProfileVectorSql(keywords)
                val finalUpsertQuery = """
                        INSERT INTO user_semantic_profiles (user_id, embedding_profile)
                        VALUES (?, ($vectorSql))
                        ON CONFLICT (user_id) DO UPDATE SET
                            embedding_profile = EXCLUDED.embedding_profile,
                            updated_at = CURRENT_TIMESTAMP;
                    """.trimIndent()
                TransactionManager.current().connection.prepareStatement(finalUpsertQuery, false)
                    .also { statement ->
                        statement[1] = userId
                        statement.executeUpdate()
                    }
            }
        }

    /**
     * Searches for user profiles by keyword using semantic similarity.
     * The input text is compared against user attributes, profile keywords, and postings.
     *
     * @param searchText The text to search for
     * @param latitude Optional latitude for location filtering
     * @param longitude Optional longitude for location filtering
     * @param radiusMeters Optional radius in meters for location filtering
     * @param limit Maximum number of results to return
     * @return List of matching user profiles with similarity scores
     */
    override suspend fun searchProfilesByKeyword(
        userId: String,
        searchText: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?,
        limit: Int,
        customWeight: Int
    ): List<UserProfileWithDistance> = dbQuery {
        // Validate input
        if (!SecurityUtils.isValidLength(searchText, 1, 1000)) {
            println("Invalid search text length: ${searchText.length}")
            return@dbQuery emptyList()
        }

        if (SecurityUtils.containsSqlInjectionPatterns(searchText)) {
            println("Search text contains dangerous patterns")
            return@dbQuery emptyList()
        }

        // Sanitize the search text
        val sanitizedSearchText = SecurityUtils.sanitizeSqlString(searchText)

        // Calculate weight multiplier: customWeight 10-100 maps to multiplier 0.5-1.2
        // Formula: multiplier = 0.5 + (customWeight - 10) * (1.2 - 0.5) / (100 - 10)
        val clampedWeight = customWeight.coerceIn(10, 100)
        val weightMultiplier = 0.5 + (clampedWeight - 10) * 0.7 / 90.0
        
        println("@@@@@@@@@@ Starting multi-stage search for: '$sanitizedSearchText' (customWeight: $customWeight, multiplier: $weightMultiplier)")

        // Stage 1: Fast keyword-only search (no embedding generation)
        val keywordResults = executeKeywordSearch(
            sanitizedSearchText,
            latitude,
            longitude,
            radiusMeters,
            limit,
            weightMultiplier
        )

        println("@@@@@@@@@@ Stage 1 complete: Found ${keywordResults.size} keyword matches")

        // Decision point: Should we enhance with semantic search?
        val hasLowKeywordScores = keywordResults.any { it.second < 0.5 }
        val useSemanticEnhancement = keywordResults.size < 10 || hasLowKeywordScores

        val userProfiles = mutableListOf<UserProfileWithDistance>()
        val userAttributes = mutableMapOf<String, MutableList<UserAttributeDto>>()

        // Stage 2: Semantic similarity search on user profiles
        if (useSemanticEnhancement) {
            val searchEmbedding = getOrGenerateEmbedding(sanitizedSearchText)
            
            if (searchEmbedding != null) {
                val semanticResults = executeSemanticProfileSearch(
                    searchEmbedding,
                    latitude,
                    longitude,
                    weightMultiplier
                )
                
                semanticResults.forEach { (profile, distance, score) ->
                    userProfiles.add(
                        UserProfileWithDistance(
                            profile = profile,
                            distanceKm = distance,
                            matchRelevancyScore = score
                        )
                    )
                    userAttributes[profile.userId] = mutableListOf()
                }
                
                println("@@@@@@@@@@ Stage 2 complete: Found ${semanticResults.size} semantic profile matches")

                // Stage 3: Semantic similarity search on user postings
                val postingResults = executeSemanticPostingSearch(
                    searchEmbedding,
                    latitude,
                    longitude,
                    radiusMeters,
                    weightMultiplier
                )
                
                postingResults.forEach { (profile, distance, score) ->
                    // Only add if not already in results
                    if (!userProfiles.any { it.profile.userId == profile.userId }) {
                        userProfiles.add(
                            UserProfileWithDistance(
                                profile = profile,
                                distanceKm = distance,
                                matchRelevancyScore = score
                            )
                        )
                        userAttributes[profile.userId] = mutableListOf()
                    }
                }
                
                println("@@@@@@@@@@ Stage 3 complete: Found ${postingResults.size} posting matches")
            }
        }

        // Add keyword-only results that weren't found in semantic search
        addKeywordOnlyResults(
            keywordResults,
            userProfiles,
            userAttributes,
            latitude,
            longitude
        )

        // Sort by relevancy score
        userProfiles.sortByDescending { it.matchRelevancyScore }

        val results = fetchAttributesForProfiles(userProfiles, userAttributes)

        println("@@@@@@@@@@ Search complete: Found ${results.size} total profiles matching '$searchText' " +
                    "(semantic enhancement: $useSemanticEnhancement)")
        results
    }

    /**
     * Stage 1: Fast keyword-only search using trigram similarity
     * Searches user attributes AND user postings (title + description)
     * Posting matches have slightly higher weight (0.4-0.5) vs attribute matches (0.15-0.3)
     */
    private suspend fun executeKeywordSearch(
        searchText: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?,
        limit: Int,
        weightMultiplier: Double? = 1.0
    ): List<Pair<String, Double>> = dbQuery {
        val minimalSimilarity = 0.3 * (weightMultiplier ?: 1.0)
        val keywordOnlyQuery = """
            WITH matching_attributes AS (
                -- PRE-FILTER: Find only attributes that match, using GIN index
                SELECT 
                    am.id,
                    am.attribute_key,
                    am.custom_user_attr_text,
                    -- Calculate similarity scores ONCE per attribute
                    word_similarity(?, am.custom_user_attr_text) as word_sim,
                    similarity(?, am.custom_user_attr_text) as trgm_sim,
                    CASE 
                        WHEN LOWER(am.custom_user_attr_text) LIKE LOWER('%' || ? || '%') THEN 1.0
                        ELSE 0.0
                    END as exact_match
                FROM attributes am
                WHERE 
                    -- Use GIN index for fast pre-filtering
                    am.custom_user_attr_text % ?
                    OR LOWER(am.custom_user_attr_text) LIKE LOWER('%' || ? || '%')
            ),
            user_match_scores AS (
                -- Join pre-filtered attributes with user attributes
                SELECT 
                    ua.user_id,
                    SUM(
                        GREATEST(ma.word_sim, ma.trgm_sim, ma.exact_match) * 
                        CASE 
                            WHEN ua.relevancy > 0.90 THEN 0.3
                            WHEN ua.relevancy > 0.75 THEN 0.25
                            ELSE 0.15
                        END
                    ) as attribute_score,
                    COUNT(*) FILTER (WHERE ua.relevancy > 0.8 AND ma.exact_match > 0) * 0.10 as priority_bonus
                FROM user_attributes ua
                JOIN matching_attributes ma ON ua.attribute_id = ma.attribute_key
                WHERE 
                    (ma.word_sim > 0.45 OR ma.trgm_sim > 0.45 OR ma.exact_match > 0)
                GROUP BY ua.user_id
            ),
            posting_match_scores AS (
                -- Search in user postings (title and description) - SLIGHTLY HIGHER WEIGHT
                SELECT 
                    p.user_id,
                    MAX(
                        -- Title matching (higher weight: 0.5-0.6 vs attribute 0.15-0.3)
                        GREATEST(
                            word_similarity(?, p.title) * 0.5,
                            similarity(?, p.title) * 0.5,
                            CASE WHEN LOWER(p.title) LIKE LOWER('%' || ? || '%') THEN 0.6 ELSE 0.0 END
                        ) +
                        -- Description matching (medium-high weight: 0.35-0.45)
                        GREATEST(
                            word_similarity(?, p.description) * 0.35,
                            similarity(?, p.description) * 0.35,
                            CASE WHEN LOWER(p.description) LIKE LOWER('%' || ? || '%') THEN 0.45 ELSE 0.0 END
                        )
                    ) as posting_score
                FROM user_postings p
                WHERE 
                    p.status = 'active'
                    AND (p.expires_at IS NULL OR p.expires_at > NOW())
                    AND (
                        p.title % ? OR p.description % ?
                        OR LOWER(p.title) LIKE LOWER('%' || ? || '%')
                        OR LOWER(p.description) LIKE LOWER('%' || ? || '%')
                    )
                GROUP BY p.user_id
            ),
            combined_scores AS (
                SELECT 
                    u.id as user_id,
                    up.name,
                    up.location,
                    -- Combine attribute and posting scores
                    COALESCE(ums.attribute_score, 0) + 
                    COALESCE(ums.priority_bonus, 0) +
                    COALESCE(pms.posting_score, 0) as keyword_score
                    ${
                        if (latitude != null && longitude != null) {
                            ", ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters"
                        } else {
                            ", NULL as distance_meters"
                        }
                    }
                FROM user_registration_data u
                INNER JOIN user_profiles up ON u.id = up.user_id
                LEFT JOIN user_match_scores ums ON u.id = ums.user_id
                LEFT JOIN posting_match_scores pms ON u.id = pms.user_id
                WHERE 
                    (ums.user_id IS NOT NULL OR pms.user_id IS NOT NULL)
                    ${
            if (latitude != null && longitude != null && radiusMeters != null) {
                "AND ST_DWithin(up.location::geography, ST_MakePoint(?, ?)::geography, ?)"
            } else {
                ""
            }
        }
            )
            SELECT 
                user_id,
                keyword_score
            FROM combined_scores
            WHERE keyword_score > 0.25
            ORDER BY keyword_score DESC
            LIMIT ?;
        """.trimIndent()

        val keywordParams = mutableListOf<Pair<IColumnType<*>, Any?>>()
        
        // Parameters for matching_attributes CTE (5 occurrences)
        repeat(5) { keywordParams.add(VarCharColumnType() to searchText) }
        
        // Parameters for posting_match_scores CTE (10 occurrences)
        // Title: word_similarity, similarity, LIKE
        repeat(3) { keywordParams.add(VarCharColumnType() to searchText) }
        // Description: word_similarity, similarity, LIKE
        repeat(3) { keywordParams.add(VarCharColumnType() to searchText) }
        // WHERE clause: title %, description %, title LIKE, description LIKE
        repeat(4) { keywordParams.add(VarCharColumnType() to searchText) }

        // Parameters for distance calculation (if location provided)
        if (latitude != null && longitude != null) {
            keywordParams.add(DoubleColumnType() to longitude)
            keywordParams.add(DoubleColumnType() to latitude)
        }

        // Parameters for geospatial filter (if location and radius provided)
        if (latitude != null && longitude != null && radiusMeters != null) {
            keywordParams.add(DoubleColumnType() to longitude)
            keywordParams.add(DoubleColumnType() to latitude)
            keywordParams.add(DoubleColumnType() to radiusMeters)
        }

        // Limit parameter
        keywordParams.add(IntegerColumnType() to limit)

        val results = mutableListOf<Pair<String, Double>>()

        TransactionManager.current().connection.prepareStatement(keywordOnlyQuery, false)
            .also { statement ->
                keywordParams.forEachIndexed { index, (_, value) ->
                    statement[index + 1] = value ?: ""
                }

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val userId = rs.getString("user_id")
                    val score = rs.getDouble("keyword_score")
                    if (score >= minimalSimilarity) {
                        results.add(userId to (score + 0.5))
                    }
                }
            }

        results
    }

    /**
     * Get or generate embedding for search text with caching
     */
    private suspend fun getOrGenerateEmbedding(searchText: String): FloatArray? = dbQuery {
        var cachedEmbedding = embeddingCache.get(searchText)

        if (cachedEmbedding == null) {
            println("@@@@@@@@@@ Cache MISS: Generating embedding for '$searchText'")
            try {
                val embeddingGenQuery = """
                    SELECT ai.ollama_embed('${AiConfig.embedModel}', ?, host => '${AiConfig.ollamaHost}')::text as embedding
                """.trimIndent()

                val stmt = TransactionManager.current().connection.prepareStatement(embeddingGenQuery, false)
                stmt[1] = searchText
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val embeddingStr = rs.getString("embedding")
                    val embedding = embeddingStr.trim('[', ']')
                        .split(',')
                        .map { it.trim().toFloatOrNull() ?: 0f }
                        .toFloatArray()

                    if (embedding.isNotEmpty()) {
                        embeddingCache.put(searchText, embedding)
                        cachedEmbedding = embedding
                        println("@@@@@@@@@@ Cached embedding (${embedding.size} dimensions)")
                    }
                }
            } catch (e: Exception) {
                println("@@@@@@@@@@ Warning: Could not generate embedding: ${e.message}")
            }
        } else {
            println("@@@@@@@@@@ Cache HIT: Using cached embedding")
        }

        cachedEmbedding
    }

    /**
     * Stage 2: Semantic similarity search on user profiles
     */
    private suspend fun executeSemanticProfileSearch(
        searchEmbedding: FloatArray,
        latitude: Double?,
        longitude: Double?,
        weightMultiplier: Double? = 1.0
    ): List<Triple<UserProfile, Double, Double>> = dbQuery {
        val embeddingArray = searchEmbedding.joinToString(",", "[", "]")
        val minimalSimilarity = 0.6 * (weightMultiplier ?: 1.0)

        val semanticQuery = """
            WITH search_embedding AS (
                SELECT '$embeddingArray'::vector as embedding
            ),
            candidates AS (
                SELECT
                    u.id as user_id,
                    up.name,
                    up.location as location,
                    COALESCE(
                        CASE 
                            WHEN usp.embedding_haves IS NOT NULL 
                            THEN 1 - ((SELECT embedding FROM search_embedding) <=> usp.embedding_haves)
                            ELSE 0.0
                        END,
                        0.0
                    ) as haves_similarity,
                    COALESCE(
                        CASE 
                            WHEN usp.embedding_needs IS NOT NULL 
                            THEN 1 - (usp.embedding_needs <=> (SELECT embedding FROM search_embedding))
                            ELSE 0.0
                        END,
                        0.0
                    ) as needs_similarity,
                    COALESCE(
                        1 - ((SELECT embedding FROM search_embedding) <=> usp.embedding_profile),
                        0.0
                    ) as profile_similarity
                    ${
                        if (latitude != null && longitude != null) {
                            ", ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters"
                        } else {
                            ", NULL as distance_meters"
                        }
                    }
                FROM user_registration_data u
                INNER JOIN user_profiles up ON u.id = up.user_id
                LEFT JOIN user_semantic_profiles usp ON u.id = usp.user_id
                WHERE 
                    (usp.embedding_haves IS NOT NULL 
                     OR usp.embedding_needs IS NOT NULL)
            )
            SELECT 
                user_id,
                name,
                location,
                haves_similarity,
                needs_similarity,
                profile_similarity,
                distance_meters,
                -- Combined score: 60% best match, 40% average
                (0.6 * GREATEST(haves_similarity, needs_similarity, profile_similarity) +
                 0.4 * ((haves_similarity + needs_similarity + profile_similarity) / 
                        NULLIF((CASE WHEN haves_similarity > 0 THEN 1 ELSE 0 END +
                                CASE WHEN needs_similarity > 0 THEN 1 ELSE 0 END +
                                CASE WHEN profile_similarity > 0 THEN 1 ELSE 0 END), 0))) as semantic_score
            FROM candidates
            WHERE (haves_similarity > 0 OR needs_similarity > 0 OR profile_similarity > 0)
                AND (0.6 * GREATEST(haves_similarity, needs_similarity, profile_similarity) +
                     0.4 * ((haves_similarity + needs_similarity + profile_similarity) / 
                            NULLIF((CASE WHEN haves_similarity > 0 THEN 1 ELSE 0 END +
                                    CASE WHEN needs_similarity > 0 THEN 1 ELSE 0 END +
                                    CASE WHEN profile_similarity > 0 THEN 1 ELSE 0 END), 0))) > 0.6
            ORDER BY semantic_score DESC, distance_meters ASC NULLS LAST
            LIMIT 25;
        """.trimIndent()

        val params = mutableListOf<Pair<IColumnType<*>, Any?>>()
        if (latitude != null && longitude != null) {
            params.add(DoubleColumnType() to longitude)
            params.add(DoubleColumnType() to latitude)
        }

        val results = mutableListOf<Triple<UserProfile, Double, Double>>()

        TransactionManager.current().connection.prepareStatement(semanticQuery, false)
            .also { statement ->
                params.forEachIndexed { index, (_, value) ->
                    statement[index + 1] = value ?: ""
                }

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val userId = rs.getString("user_id")
                    val semanticScore = rs.getDouble("semantic_score")
                        .let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
                    val distanceMeters = rs.getDouble("distance_meters")

                    if (semanticScore >= minimalSimilarity) {
                        val (longitude, latitude) = parseLocation(rs)
                        
                        val profile = UserProfile(
                            userId = userId,
                            name = rs.getString("name"),
                            latitude = latitude,
                            longitude = longitude,
                            attributes = emptyList(),
                            profileKeywordDataMap = mapOf()
                        )
                        
                        results.add(Triple(
                            profile,
                            if (distanceMeters != 0.0) distanceMeters / 1000 else 0.0,
                            semanticScore
                        ))
                    }
                }
            }

        results
    }

    /**
     * Stage 3: Semantic similarity search on user postings
     */
    private suspend fun executeSemanticPostingSearch(
        searchEmbedding: FloatArray,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?,
        weightMultiplier: Double? = 1.0
    ): List<Triple<UserProfile, Double, Double>> = dbQuery {
        val embeddingArray = searchEmbedding.joinToString(",", "[", "]")
        val minimalSimilarity = 0.65 * (weightMultiplier ?: 1.0)
        val postingQuery = """
            WITH search_embedding AS (
                SELECT '$embeddingArray'::vector as embedding
            )
            SELECT DISTINCT ON (p.user_id)
                p.user_id,
                up.name,
                up.location,
                (1 - (p.embedding::vector <=> (SELECT embedding FROM search_embedding))) as posting_similarity
                ${
                    if (latitude != null && longitude != null) {
                        ", ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters"
                    } else {
                        ", NULL as distance_meters"
                    }
                }
            FROM user_postings p
            INNER JOIN user_profiles up ON p.user_id = up.user_id
            WHERE 
                p.embedding IS NOT NULL
                AND p.status = 'active'
                AND (p.expires_at IS NULL OR p.expires_at > NOW())
                ${
                    if (latitude != null && longitude != null && radiusMeters != null) {
                        "AND ST_DWithin(up.location::geography, ST_MakePoint(?, ?)::geography, ?)"
                    } else {
                        ""
                    }
                }
                AND (1 - (p.embedding::vector <=> (SELECT embedding FROM search_embedding))) > 0.65
            ORDER BY p.user_id, posting_similarity DESC
            LIMIT 20;
        """.trimIndent()

        val params = mutableListOf<Pair<IColumnType<*>, Any?>>()
        
        if (latitude != null && longitude != null) {
            params.add(DoubleColumnType() to longitude)
            params.add(DoubleColumnType() to latitude)
        }
        
        if (latitude != null && longitude != null && radiusMeters != null) {
            params.add(DoubleColumnType() to longitude)
            params.add(DoubleColumnType() to latitude)
            params.add(DoubleColumnType() to radiusMeters)
        }

        val results = mutableListOf<Triple<UserProfile, Double, Double>>()

        TransactionManager.current().connection.prepareStatement(postingQuery, false)
            .also { statement ->
                params.forEachIndexed { index, (_, value) ->
                    statement[index + 1] = value ?: ""
                }

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val userId = rs.getString("user_id")
                    val postingSimilarity = rs.getDouble("posting_similarity")
                        .let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
                    val distanceMeters = rs.getDouble("distance_meters")

                    println("@@@@@@@@@@@ Posting similarity: $postingSimilarity")
                    if (postingSimilarity >= minimalSimilarity) {
                        val (longitude, latitude) = parseLocation(rs)
                        
                        val profile = UserProfile(
                            userId = userId,
                            name = rs.getString("name"),
                            latitude = latitude,
                            longitude = longitude,
                            attributes = emptyList(),
                            profileKeywordDataMap = mapOf()
                        )
                        
                        results.add(Triple(
                            profile,
                            if (distanceMeters != 0.0) distanceMeters / 1000 else 0.0,
                            postingSimilarity
                        ))
                    }
                }
            }

        results
    }

    /**
     * Add keyword-only results that weren't found in semantic search
     */
    private suspend fun addKeywordOnlyResults(
        keywordResults: List<Pair<String, Double>>,
        userProfiles: MutableList<UserProfileWithDistance>,
        userAttributes: MutableMap<String, MutableList<UserAttributeDto>>,
        latitude: Double?,
        longitude: Double?
    ) = dbQuery {
        val userIdList = keywordResults
            .filter { !userProfiles.any { p -> p.profile.userId == it.first } }
            .map { it.first }
            .joinToString(",") { "'$it'" }

        if (userIdList.isEmpty()) return@dbQuery

        val simpleProfileQuery = """
            SELECT
                u.id as user_id,
                up.name,
                up.location as location
                ${
                    if (latitude != null && longitude != null) {
                        ", ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters"
                    } else {
                        ", NULL as distance_meters"
                    }
                }
            FROM user_registration_data u
            INNER JOIN user_profiles up ON u.id = up.user_id
            WHERE u.id IN ($userIdList);
        """.trimIndent()

        val simpleParams = mutableListOf<Pair<IColumnType<*>, Any?>>()
        if (latitude != null && longitude != null) {
            simpleParams.add(DoubleColumnType() to longitude)
            simpleParams.add(DoubleColumnType() to latitude)
        }

        TransactionManager.current().connection.prepareStatement(simpleProfileQuery, false)
            .also { statement ->
                simpleParams.forEachIndexed { index, (_, value) ->
                    statement[index + 1] = value ?: ""
                }

                val rs = statement.executeQuery()
                while (rs.next()) {
                    val foundUserId = rs.getString("user_id")
                    val distanceMeters = rs.getDouble("distance_meters")
                    val (longitude, latitude) = parseLocation(rs)

                    val keywordScore = keywordResults.first { foundUserId == it.first }.second
                    userProfiles.add(
                        UserProfileWithDistance(
                            profile = UserProfile(
                                userId = foundUserId,
                                name = rs.getString("name"),
                                latitude = latitude,
                                longitude = longitude,
                                attributes = emptyList(),
                                profileKeywordDataMap = mapOf()
                            ),
                            distanceKm = if (distanceMeters != 0.0) distanceMeters / 1000 else 0.0,
                            matchRelevancyScore = keywordScore
                        )
                    )
                    userAttributes[foundUserId] = mutableListOf()
                }
            }
    }

    /**
     * Fetches active posting IDs for a user.
     * Returns a list of posting IDs that are active and not expired.
     */
    private suspend fun fetchActivePostingIds(userId: String): List<String> = dbQuery {
        val sql = """
            SELECT id
            FROM user_postings
            WHERE user_id = ?
                AND status = 'active'
                AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at DESC;
        """.trimIndent()

        val postingIds = mutableListOf<String>()

        TransactionManager.current().connection.prepareStatement(sql, false)
            .also { statement ->
                statement[1] = userId
                val rs = statement.executeQuery()
                while (rs.next()) {
                    postingIds.add(rs.getString("id"))
                }
            }

        postingIds
    }

    /**
     * Fetches active posting IDs for multiple users at once.
     * Returns a map of userId to list of posting IDs.
     */
    internal suspend fun fetchActivePostingIdsForUsers(userIds: List<String>): Map<String, List<String>> =
        dbQuery {
            if (userIds.isEmpty()) return@dbQuery emptyMap()

            val inClause = userIds.joinToString(", ") { "'$it'" }

            val sql = """
            SELECT user_id, id
            FROM user_postings
            WHERE user_id IN ($inClause)
                AND status = 'active'
                AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at DESC;
        """.trimIndent()

            val result = mutableMapOf<String, MutableList<String>>()

            // Initialize empty lists for all users
            userIds.forEach { userId ->
                result[userId] = mutableListOf()
            }

            TransactionManager.current().exec(sql) { rs ->
                while (rs.next()) {
                    val userId = rs.getString("user_id")
                    val postingId = rs.getString("id")
                    result[userId]?.add(postingId)
                }
            }

            result
    }

    /**
     * Fetches ui_style_hint for a list of attributes from their associated categories.
     * Returns a map of attributeId to ui_style_hint (from the category with highest relevancy).
     */
    internal suspend fun fetchUiStyleHintsForAttributes(attributeIds: List<String>): Map<String, String?> =
        dbQuery {
            if (attributeIds.isEmpty()) return@dbQuery emptyMap()

            val inClause = attributeIds.joinToString(", ") { "'$it'" }

            val sql = """
            SELECT
                am.attribute_key,
                c.ui_style_hint,
                acl.relevancy,
                ROW_NUMBER() OVER (PARTITION BY am.attribute_key ORDER BY acl.relevancy DESC) as rn
            FROM attributes am
            JOIN attribute_categories_link acl ON am.id = acl.attribute_id
            JOIN categories c ON acl.category_id = c.id
            WHERE am.attribute_key IN ($inClause)
                AND c.ui_style_hint IS NOT NULL
        """.trimIndent()

            val result = mutableMapOf<String, String?>()

            TransactionManager.current().exec(sql) { rs ->
                while (rs.next()) {
                    val rowNum = rs.getInt("rn")
                    // Only take the first row (highest relevancy) for each attribute
                    if (rowNum == 1) {
                        val attributeKey = rs.getString("attribute_key")
                        val uiStyleHint = rs.getString("ui_style_hint")
                        result[attributeKey] = uiStyleHint
                    }
                }
            }
            result
        }

    private suspend fun fetchAttributesForProfiles(userProfiles: List<UserProfileWithDistance>,
                                                   userAttributes: MutableMap<String, MutableList<UserAttributeDto>>)
    : List<UserProfileWithDistance> {
        // Fetch attributes for all found users
        val userIds = userProfiles.map { it.profile.userId }
        if (userIds.isNotEmpty()) {
            // First collect all attributes
            val allAttributeIds = mutableListOf<String>()
            UserAttributesTable
                .selectAll()
                .where { UserAttributesTable.userId inList userIds }
                .forEach { row ->
                    val attrUserId = row[UserAttributesTable.userId]
                    val attrId = row[UserAttributesTable.attributeId]
                    allAttributeIds.add(attrId)
                    userAttributes[attrUserId]?.add(
                        UserAttributeDto(
                            attributeId = attrId,
                            type = UserAttributeType.entries.indexOf(row[UserAttributesTable.type]),
                            relevancy = row[UserAttributesTable.relevancy].toDouble(),
                            description = row.getOrNull(UserAttributesTable.description),
                            uiStyleHint = null // Will be filled in next step
                        )
                    )
                }

            // Fetch ui_style_hints for all attributes at once
            val uiStyleHints = fetchUiStyleHintsForAttributes(allAttributeIds)

            // Update attributes with ui_style_hints
            userAttributes.forEach { (userId, attrs) ->
                userAttributes[userId] = attrs.map { attr ->
                    attr.copy(uiStyleHint = uiStyleHints[attr.attributeId])
                }.toMutableList()
            }
        }

        // Fetch active posting IDs for all users
        val postingIdsByUser = fetchActivePostingIdsForUsers(userIds)

        // Combine profiles with their attributes and posting IDs
        val results = userProfiles.map { profileWithDistance ->
            profileWithDistance.copy(
                profile = profileWithDistance.profile.copy(
                    attributes = userAttributes[profileWithDistance.profile.userId] ?: emptyList(),
                    activePostingIds = postingIdsByUser[profileWithDistance.profile.userId] ?: emptyList()
                )
            )
        }
        return results
    }

    private fun parseLocation(rs: ResultSet): Pair<Double?, Double?> {
        // Parse location from string
        val lStr = rs.getObject("location")?.toString()
        if (lStr != null && lStr.contains("POINT")) {
            val locationStr =
                lStr.substring(lStr.indexOf("POINT") + 6, lStr.indexOf(")"))
            val coords = locationStr.split(" ")
            return Pair(coords[0].trim().toDouble(), coords[1].trim().toDouble())
        } else {
            return Pair(null, null)
        }
    }

}
