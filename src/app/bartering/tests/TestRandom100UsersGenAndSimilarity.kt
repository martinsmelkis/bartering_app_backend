package app.bartering.tests

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.postgis.jdbc.geometry.Point
import app.bartering.extensions.DatabaseFactory
import app.bartering.extensions.normalizeAttributeForDBProcessing
import app.bartering.features.ai.data.ExpandedInterests
import app.bartering.features.attributes.db.AttributesMasterTable
import app.bartering.features.attributes.db.UserAttributesTable
import app.bartering.features.attributes.model.UserAttributeType
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.profile.db.UserRegistrationDataTable
import app.bartering.features.profile.model.UserProfile
import app.bartering.features.profile.db.UserProfilesTable
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.koin.java.KoinJavaComponent.inject
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object TestRandom100UsersGenAndSimilarity {

    suspend fun execute() {

        val profilesDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
        var usersToInsert: List<Triple<String, String, String>> = listOf()
        DatabaseFactory.dbQuery {
            val userCount = 100
            val parisCenterLat = 2.3522
            val parisCenterLon = 48.8566

            val coreProfileKeywords = listOf(
                "Nature, outdoors, gardening, animals, environment, hiking, plants, sustainability, wildlife, ecology, forests, mountains, camping, fishing, farming, conservation, organic, natural, green living, eco-friendly, botanical, outdoor adventure, wilderness, backpacking",
                "Sports, physical exercise, physical work, partying, dancing, mechanisms, hands-on activities, fitness, athletics, training, competitions, movement, energy, active lifestyle, gym, workout, strength, endurance, manual labor, craftsmanship, building, repairs, nightlife, events",
                "Business, entrepreneurship, paid work, making contacts, money matters, finance, career, professional, networking, investments, trading, startups, corporate, management, consulting, sales, marketing, leadership, strategy, economics, commerce, job opportunities, employment",
                "Art, spirituality, philosophy, culture, music, crafts, creativity, design, history, meditation, mindfulness, artistic expression, handmade, DIY, visual arts, performing arts, literature, poetry, aesthetics, cultural heritage, traditions, consciousness, self-discovery, contemplation, galleries, museums",
                "Communication, chat, social activities, casual conversation, local events, neighborhood, community gatherings, socializing, networking events, meetups, friendly exchanges, leisure time, entertainment, hobbies, interests, discussions, forums, group activities, public spaces",
                "Volunteering, open-ended help, free exchange, consulting, non-specific assistance, community support, charity, giving back, mutual aid, neighbors helping neighbors, goodwill, kindness, sharing, cooperation, solidarity, social responsibility, humanitarian, service, outreach",
                "Technology, learning, education, innovation, brainstorming, ideas, science, software, programming, coding, digital, tech, computing, engineering, research, development, training, courses, tutorials, knowledge sharing, problem-solving, inventions, STEM, data, algorithms, automation"
            )

            val sampleAttributeKeys =
                ExpandedInterests.all.map { it.normalizeAttributeForDBProcessing() }

            usersToInsert = (1..userCount).map {
                val id = UUID.randomUUID().toString()
                Triple(id, "", "${id}@test.com")
            }

            UserRegistrationDataTable.batchInsert(usersToInsert) { (id, name, email) ->
                this[UserRegistrationDataTable.id] = id
                this[UserRegistrationDataTable.publicKey] = "key_$id"
            }

            // Get the current user count before inserting new users
            val existingUserCount = UserProfilesTable.selectAll().count()

            val profilesToInsert = usersToInsert.mapIndexed { index, (id, _, _) ->
                // Create a random point within ~5km of the center of Paris
                val randomAngle = Random.Default.nextDouble(0.0, 2 * Math.PI)
                val randomRadius = Random.Default.nextDouble(0.0, 5000.0) // Radius in meters
                val latOffset = randomRadius * cos(randomAngle) / 111320.0
                val lonOffset = randomRadius * sin(randomAngle) / (40075000.0 * cos(
                    Math.toRadians(parisCenterLat)
                ) / 360)

                val profileKeywords: HashMap<String, Double> = hashMapOf()

                coreProfileKeywords.shuffled()//.take(Random.Default.nextInt(5, 8))
                    .map { profileKeywords[it] = Random.Default.nextDouble(-1.0, 1.0) }

                println("@@@@@@@@@@ Profile Keywords: $profileKeywords")

                // Generate username based on existing user count + index
                val userName = "User_${existingUserCount + index + 1}"

                Pair(
                    Triple(
                        id,
                        Point(parisCenterLat + latOffset, parisCenterLon + lonOffset)
                            .also { p -> p.srid = 4326 },
                        profileKeywords
                    ),
                    userName
                )
            }

            UserProfilesTable.batchInsert(
                profilesToInsert,
                shouldReturnGeneratedValues = false
            ) { (profile, userName) ->
                val (id, location, keywords) = profile
                this[UserProfilesTable.userId] = id
                this[UserProfilesTable.name] = userName
                this[UserProfilesTable.location] = location
                this[UserProfilesTable.profileKeywordDataMap] = keywords
            }

            val attributesToInsert = mutableListOf<Triple<String, String, UserAttributeType>>()
            usersToInsert.forEach { (userId, _, _) ->
                val numAttributes = Random.Default.nextInt(10, 16)
                sampleAttributeKeys.shuffled().distinct().take(numAttributes).forEach { attrKey ->
                    attributesToInsert.add(
                        Triple(
                            userId, attrKey,
                            if (Random.Default.nextBoolean()) UserAttributeType.SEEKING else UserAttributeType.PROVIDING
                        )
                    )
                }
            }

            UserAttributesTable.batchInsert(
                attributesToInsert,
                ignore = false,
                shouldReturnGeneratedValues = false
            ) { (userId, attrId, type) ->
                this[UserAttributesTable.userId] = userId
                this[UserAttributesTable.attributeId] = attrId
                this[UserAttributesTable.type] = type
                this[UserAttributesTable.relevancy] =
                    Random.Default.nextDouble(0.1, 1.0).toBigDecimal()
            }
        }

        coroutineScope {
            usersToInsert.forEach { it ->
                runBlocking {
                    profilesDao.updateSemanticProfile(it.first, UserAttributeType.SEEKING)
                    profilesDao.updateSemanticProfile(it.first, UserAttributeType.PROVIDING)
                    profilesDao.updateSemanticProfile(it.first, UserAttributeType.PROFILE)
                }
            }
        }

        coroutineScope {
            usersToInsert.take(10).forEach {
                runBlocking {
                    findSimilarProfiles(it.first)
                }
            }
        }

    }

    suspend fun findSimilarProfiles(userId: String) {
        DatabaseFactory.dbQuery {
            val profilesDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

            // 1. Get the current user and the list of similar profiles
            val currentUserProfile = profilesDao.getProfile(userId)
            if (currentUserProfile == null) {
                println("Could not find profile for current user: $userId")
                return@dbQuery
            }

            val similarProfiles = profilesDao.getSimilarProfiles(userId)

            // 2. Collect all attribute IDs that need to be looked up
            val allProfiles = listOf(currentUserProfile) + similarProfiles.map { it.profile }
            val allAttributeIds =
                allProfiles.flatMap { it.attributes }.map { it.attributeId }//.distinct()
            // Explicitly map Ints to EntityIDs to resolve compiler ambiguity with the inList operator.
            // 3. Fetch the plaintext for all unique attributes in a single query
            val attributeIdToTextMap = AttributesMasterTable
                .select(
                    AttributesMasterTable.attributeNameKey,
                    AttributesMasterTable.customUserAttrText
                )
                .where { AttributesMasterTable.attributeNameKey inList allAttributeIds.toList() }
                .associate { it[AttributesMasterTable.attributeNameKey] to it[AttributesMasterTable.customUserAttrText] }
            // 4. Log the current user's profile
            logUserProfileDetails(currentUserProfile, "Current User Profile", attributeIdToTextMap)
            // 5. Log the similar profiles
            println("\n--- Found ${similarProfiles.size} Similar Profiles ---")
            similarProfiles.forEachIndexed { index, profileWithDistance ->
                // In getSimilarProfiles, distanceKm is repurposed for the similarity score.
                val header = "Similar Profile #${index + 1} (Similarity: ${
                    String.format(
                        "%.2f",
                        profileWithDistance.distanceKm
                    )
                })"
                logUserProfileDetails(profileWithDistance.profile, header, attributeIdToTextMap)
            }
        }
    }

    private fun logUserProfileDetails(profile: UserProfile, header: String, attributeMap: Map<String, String?>) {
        println("\n----- $header -----")
        println("User ID: ${profile.userId}")
        val haves = profile.attributes
            .filter { UserAttributeType.entries[it.type] == UserAttributeType.PROVIDING }
            .joinToString(", ") {
                val attrText = attributeMap[it.attributeId] ?: "Unknown Attribute (ID: ${it.attributeId})"
                "$attrText (${String.format("%.2f", it.relevancy)})"
            }
        val needs = profile.attributes
            .filter { UserAttributeType.entries[it.type] == UserAttributeType.SEEKING }
            .joinToString(", ") {
                val attrText = attributeMap[it.attributeId] ?: "Unknown Attribute (ID: ${it.attributeId})"
                "$attrText (${String.format("%.2f", it.relevancy)})"
            }
        val profileKeywords = profile.profileKeywordDataMap

        println("  Haves: ${haves.ifEmpty { "None" }}")
        println("  Needs: ${needs.ifEmpty { "None" }}")
        println("  Profile: ${profileKeywords?.ifEmpty { "None" }}")
    }

}