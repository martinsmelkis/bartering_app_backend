package org.barter.tests

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.postgis.jdbc.geometry.Point
import org.barter.extensions.DatabaseFactory
import org.barter.extensions.normalizeAttributeForDBProcessing
import org.barter.features.attributes.dao.AttributesDaoImpl
import org.barter.features.attributes.db.AttributesMasterTable
import org.barter.features.attributes.db.UserAttributesTable
import org.barter.features.attributes.model.UserAttributeType
import org.barter.features.profile.dao.UserProfileDaoImpl
import org.barter.features.profile.db.UserRegistrationDataTable
import org.barter.features.profile.model.UserProfile
import org.barter.features.profile.db.UserProfilesTable
import org.jetbrains.exposed.sql.batchInsert
import org.koin.java.KoinJavaComponent.inject
import java.util.UUID
import kotlin.random.Random

/**
 * Test class that generates archetypical users grouped by personality types
 * to verify the matching algorithm's accuracy and semantic profile generation
 */
object TestArchetypeUsersGenAndSimilarity {

    // Core profile keywords representing different life domains
    val coreProfileKeywords = listOf(
        "Nature, outdoors, gardening, animals, environment, hiking, plants, sustainability, wildlife, ecology, forests, mountains, camping, fishing, farming, conservation, organic, natural, green living, eco-friendly, botanical, outdoor adventure, wilderness, backpacking, greenhouse, seeds, trails, wilderness, recycling, upcycling, flowers, herbs",
        "Sports, physical exercise, physical work, partying, dancing, mechanisms, hands-on activities, fitness, athletics, fitness training, sports competition, movement, energy, active lifestyle, gym, workout, strength, manual labor, DIY, craftsmanship, building, equipment repair, nightlife, sports equipment, workout planning, fitness training, sports coaching, electrician, plumbing, furniture, metalworking, car maintenance",
        "Business, entrepreneurship, paid work, making contacts, money matters, finance, career, professional development, networking, investments, trading, startups, corporate, management, consulting, sales, marketing, leadership, business strategy, economics, commerce, job opportunities, employment, project management, accounting, legal, laws, event planning, public speaking, motivation",
        "Art, spirituality, philosophy, culture, music, crafts, creativity, design, history, meditation, mindfulness, artistic expression, handmade, visual arts, performing arts, literature, poetry, aesthetics, cultural heritage, traditions, consciousness, self-discovery, contemplation, galleries, museums, photography, UI/UX Design, Animation, Illustration, Video editing, painting, Sculpting, mysticism, books, reading, writing, musician, musical instruments, vocals",
        "Communication, chat, social activities, casual conversation, local events, neighborhood, community gatherings, socializing, networking events, meetups, friendly exchanges, leisure time, entertainment, hobbies, interests, discussions, forums, group activities, public spaces, tutoring, advice, language exchange, translation, event hosting, public relations, dating, social, contacts",
        "Volunteering, open-ended help, free exchange, consulting, non-specific assistance, community support, charity, giving back, mutual aid, neighbors helping neighbors, goodwill, kindness, sharing, cooperation, solidarity, social responsibility, humanitarian, service, outreach, collaboration, pet sitting, cooking, low-effort assistance, ridesharing, tool lending, equipment lending, errands",
        "Technology, learning, education, innovation, brainstorming, ideas, science, software, programming, coding, digital, tech, computing, engineering, research and development, courses, tutorials, knowledge sharing, problem-solving, inventions, STEM, data, algorithms, automation, artificial intelligence, software development, Python, Java, Kotlin, JavaScript, Web development, mobile, apps, Tech, gadgets, electronics, computers, hardware, smartphones, routers, networking, wifi"
    )

    // Archetype definitions with profile keywords and attributes
    private data class Archetype(
        val name: String,
        val description: String,
        val profileKeywordWeights: Map<String, Double>, // Keyword -> Weight (-1.0 to 1.0)
        val providingAttributes: List<String>, // What they offer
        val seekingAttributes: List<String>, // What they need
        val userCount: Int = 10
    )

    private val archetypes = listOf(
        Archetype(
            name = "Tech Nerds",
            description = "Introverted, tech-savvy individuals passionate about programming and innovation",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[6] to 0.95,  // Technology - HIGH
                coreProfileKeywords[3] to 0.4,  // Art/Philosophy - MEDIUM
                coreProfileKeywords[1] to 0.1, // Sports/Physical - LOW
                coreProfileKeywords[4] to 0.2, // Social activities - LOW
                coreProfileKeywords[2] to 0.2,  // Business - MEDIUM-LOW
                // ADD: Specific tech interests for better keyword matching
                "Programming coding software development Python Java Kotlin JavaScript TypeScript web development mobile apps" to 0.9,
                "Video games gaming esports Age of Empires Civilization strategy games RTS simulation PC gaming Steam" to 0.95,
                "Technology podcasts tech news software engineering computer science AI machine learning" to 0.75,
                "Open source GitHub GitLab coding projects software collaboration Linux" to 0.70,
                "Tech gadgets electronics computers hardware smartphones routers networking wifi" to 0.65,
            ),
            providingAttributes = listOf(
                "Programming", "Software Development", "Computer Repair", "Web Development",
                "Data Analysis", "Technical Support", "Game Development", "Database Management",
                "Cybersecurity", "App Development"
            ),
            seekingAttributes = listOf(
                "Graphic Design", "UI/UX Design", "Marketing Strategy", "Social Media Management",
                "Public Speaking", "Networking", "Business Development", "Fitness Training"
            )
        ),

        Archetype(
            name = "Athletes & Fitness Enthusiasts",
            description = "Active, physical individuals who love sports and outdoor activities",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[1] to 0.95, // Sports/Physical - VERY HIGH
                coreProfileKeywords[0] to 0.7,  // Nature/Outdoors - HIGH
                coreProfileKeywords[4] to 0.6,  // Social activities - HIGH
                coreProfileKeywords[6] to 0.1, // Technology - LOW
                coreProfileKeywords[3] to 0.2, // Art/Philosophy - LOW
            ),
            providingAttributes = listOf(
                "Fitness Training", "Sports Coaching", "Nutrition Advice", "Yoga Instruction",
                "Running Partner", "Hiking Guide", "Team Sports", "Physical Therapy",
                "Outdoor Activities", "Dance Lessons"
            ),
            seekingAttributes = listOf(
                "Massage Therapy", "Meal Preparation", "Sports Equipment", "Workout Planning",
                "Photography", "Video Editing", "Music Production", "Web Development"
            )
        ),

        Archetype(
            name = "Creative Artists",
            description = "Artistic, spiritual individuals passionate about culture and creativity",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[3] to 0.95, // Art/Philosophy - VERY HIGH
                coreProfileKeywords[4] to 0.5,  // Social activities - MEDIUM
                coreProfileKeywords[0] to 0.3,  // Nature - MEDIUM-LOW
                coreProfileKeywords[2] to 0.2, // Business - LOW
                coreProfileKeywords[1] to 0.1, // Sports - LOW
            ),
            providingAttributes = listOf(
                "Painting", "Drawing", "Music Lessons", "Graphic Design", "Photography",
                "Writing", "Poetry", "Crafts", "Sculpture", "UI/UX Design",
                "Video Editing", "Animation", "Illustration"
            ),
            seekingAttributes = listOf(
                "Exhibition Space",
                "Art Supplies",
                "Music Equipment",
                "Studio Space",
                "Photography Equipment",
                "Marketing Strategy",
                "Web Development",
                "Business Consulting"
            )
        ),

        Archetype(
            name = "Eco Warriors & Nature Lovers",
            description = "Environmentally conscious individuals passionate about sustainability",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[0] to 0.95, // Nature/Environment - VERY HIGH
                coreProfileKeywords[5] to 0.7,  // Volunteering - HIGH
                coreProfileKeywords[1] to 0.4,  // Physical activities - MEDIUM
                coreProfileKeywords[2] to 0.6, // Business - LOW
                coreProfileKeywords[6] to 0.1,  // Technology - NEUTRAL
                // ADD: Specific farming/outdoors interests
                "Farming agriculture organic farming John Deere tractors farm equipment rural life countryside" to 0.90,
                "Gardening plants vegetables herbs flowers seeds composting greenhouse permaculture" to 0.88,
                "Animals livestock chickens cows goats sheep animal husbandry pets dogs cats" to 0.85,
                "Outdoor activities hiking camping fishing hunting nature walks trails wilderness" to 0.80,
                "Sustainability eco-friendly zero waste recycling renewable energy solar panels" to 0.75,
            ),
            providingAttributes = listOf(
                "Gardening",
                "Composting",
                "Sustainable Living",
                "Plant Care",
                "Environmental Education",
                "Organic Farming",
                "Recycling",
                "Permaculture",
                "Herbalism",
                "Animal Care"
            ),
            seekingAttributes = listOf(
                "Land Access", "Garden Tools", "Seeds", "Compost Materials", "Carpentry",
                "Plumbing", "Solar Panel Installation", "Bicycle Repair", "Pottery"
            )
        ),

        Archetype(
            name = "Business Professionals",
            description = "Career-focused individuals with strong business acumen",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[2] to 0.95, // Business - VERY HIGH
                coreProfileKeywords[6] to 0.5,  // Technology - MEDIUM
                coreProfileKeywords[4] to 0.6,  // Social/Networking - HIGH
                coreProfileKeywords[0] to 0.4, // Nature - LOW
                coreProfileKeywords[5] to 0.1,  // Volunteering - LOW-NEUTRAL
            ),
            providingAttributes = listOf(
                "Business Consulting",
                "Financial Planning",
                "Marketing Strategy",
                "Project Management",
                "Accounting",
                "Legal Advice",
                "Business Development",
                "Networking",
                "Sales",
                "Event Planning",
                "Public Speaking",
                "Leadership Training"
            ),
            seekingAttributes = listOf(
                "Web Development", "Graphic Design", "Content Writing", "Social Media Management",
                "Video Production", "Translation", "Personal Assistant", "IT Support"
            )
        ),

        Archetype(
            name = "Community Volunteers",
            description = "Altruistic individuals dedicated to helping others and community service",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[5] to 0.95, // Volunteering - VERY HIGH
                coreProfileKeywords[4] to 0.8,  // Social activities - HIGH
                coreProfileKeywords[0] to 0.4,  // Nature - MEDIUM
                coreProfileKeywords[2] to 0.3, // Business - LOW
                coreProfileKeywords[1] to 0.2,  // Physical - MEDIUM-LOW
            ),
            providingAttributes = listOf(
                "Tutoring", "Mentoring", "Childcare", "Elderly Care", "Community Organizing",
                "Food Distribution", "Language Teaching", "Counseling", "Event Organizing",
                "Neighborhood Help", "Pet Sitting", "House Sitting"
            ),
            seekingAttributes = listOf(
                "Transportation", "Cooking", "Home Repair", "Legal Advice", "Medical Advice",
                "Technical Support", "Translation", "Photography", "Music Performance"
            )
        ),

        Archetype(
            name = "Practical Makers & DIY Enthusiasts",
            description = "Hands-on individuals who love building, fixing, and creating physical things",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[1] to 0.7,  // Physical work - HIGH
                coreProfileKeywords[6] to 0.4,  // Technology - MEDIUM
                coreProfileKeywords[3] to 0.3,  // Crafts - MEDIUM
                coreProfileKeywords[0] to 0.5,  // Outdoors - MEDIUM
                coreProfileKeywords[2] to 0.0,  // Business - NEUTRAL
            ),
            providingAttributes = listOf(
                "Carpentry", "Plumbing", "Electrical Work", "Car Repair", "Bicycle Repair",
                "Home Renovation", "Furniture Making", "Electronics Repair", "Welding",
                "3D Printing", "Woodworking", "Metalworking"
            ),
            seekingAttributes = listOf(
                "Design Services", "Project Planning", "Material Sourcing", "Tool Rental",
                "Photography", "Marketing", "Bookkeeping", "Website Design"
            )
        ),

        Archetype(
            name = "Social Butterflies",
            description = "Extroverted, socially active individuals who thrive on connection",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[4] to 0.95, // Social activities - VERY HIGH
                coreProfileKeywords[1] to 0.6,  // Physical/Partying - HIGH
                coreProfileKeywords[5] to 0.5,  // Volunteering - MEDIUM
                coreProfileKeywords[6] to 0.2, // Technology - LOW
                coreProfileKeywords[0] to 0.2,  // Nature - MEDIUM-LOW
            ),
            providingAttributes = listOf(
                "Event Planning", "Party Organizing", "Networking", "Social Media Management",
                "Public Relations", "Host/Hostess", "Tour Guide", "Language Exchange",
                "Conversation Partner", "Introduction Services", "Dating Advice"
            ),
            seekingAttributes = listOf(
                "Photography", "DJ Services", "Catering", "Venue Access", "Entertainment",
                "Graphic Design", "Video Editing", "Music Performance", "Bartending"
            )
        ),

        Archetype(
            name = "Wellness & Spiritual Seekers",
            description = "Mindful individuals focused on personal growth and holistic well-being",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[3] to 0.8,  // Spirituality/Philosophy - HIGH
                coreProfileKeywords[1] to 0.5,  // Yoga/Physical - MEDIUM
                coreProfileKeywords[0] to 0.6,  // Nature - MEDIUM-HIGH
                coreProfileKeywords[5] to 0.6,  // Volunteering - MEDIUM-HIGH
                coreProfileKeywords[2] to 0.2, // Business - LOW
            ),
            providingAttributes = listOf(
                "Yoga Instruction",
                "Meditation",
                "Life Coaching",
                "Counseling",
                "Massage Therapy",
                "Reiki",
                "Tarot Reading",
                "Nutrition Counseling",
                "Breathwork",
                "Mindfulness Training",
                "Herbalism",
                "Energy Healing"
            ),
            seekingAttributes = listOf(
                "Studio Space", "Music Therapy", "Art Supplies", "Garden Space",
                "Healthy Cooking", "Natural Remedies", "Spiritual Books", "Retreat Planning"
            )
        ),

        Archetype(
            name = "Balanced Generalists",
            description = "Well-rounded individuals with diverse interests and moderate involvement",
            profileKeywordWeights = mapOf(
                coreProfileKeywords[4] to 0.5,  // Social - MEDIUM
                coreProfileKeywords[0] to 0.3,  // Nature - MEDIUM-LOW
                coreProfileKeywords[1] to 0.2,  // Physical - MEDIUM-LOW
                coreProfileKeywords[6] to 0.3,  // Technology - MEDIUM-LOW
                coreProfileKeywords[3] to 0.2,  // Art - MEDIUM-LOW
                coreProfileKeywords[2] to 0.2,  // Business - MEDIUM-LOW
                coreProfileKeywords[5] to 0.4,  // Volunteering - MEDIUM
            ),
            providingAttributes = listOf(
                "General Help", "Conversation", "Advice", "Pet Sitting", "House Sitting",
                "Cooking", "Cleaning", "Shopping Assistance", "Translation", "Basic Tutoring",
                "Companionship", "Errands"
            ),
            seekingAttributes = listOf(
                "Home Repair", "Car Repair", "Computer Help", "Gardening Help",
                "Moving Assistance", "Language Practice", "Skill Teaching", "Professional Advice"
            )
        )
    )

    suspend fun execute() {
        val profilesDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
        val attributesDao: AttributesDaoImpl by inject(AttributesDaoImpl::class.java)
        val allUsers = mutableListOf<Triple<String, String, String>>()
        val userToArchetypeMap = mutableMapOf<String, String>()

        val parisCenterLat = 2.3622
        val parisCenterLon = 48.9566

        // Generate users for each archetype
        archetypes.forEach { archetype ->
            println("\n=== Generating ${archetype.userCount} users for archetype: ${archetype.name} ===")
            println("Description: ${archetype.description}")

            val archetypeUsers = (1..archetype.userCount).map { index ->
                val id = UUID.randomUUID().toString()
                val name = "${archetype.name.replace(" ", "_")}_user_${index}"
                userToArchetypeMap[id] = archetype.name
                Triple(id, name, "$name@test.com")
            }

            allUsers.addAll(archetypeUsers)

            // STEP 1: Create attributes FIRST (outside any transaction)
            val attrCustomNamesList =
                (archetype.providingAttributes + archetype.seekingAttributes).toSet()
            println("Creating ${attrCustomNamesList.size} unique attributes for archetype ${archetype.name}...")

            runBlocking {
                attrCustomNamesList.forEach { attrName ->
                    try {
                        attributesDao.findOrCreate(attrName)
                    } catch (e: Exception) {
                        println("Error creating attribute '$attrName': ${e.message}")
                    }
                }
                attributesDao.populateMissingEmbeddings()
            }

            println("All attributes created successfully!")

            // STEP 2: Now do all the database inserts in one transaction
            DatabaseFactory.dbQuery {
                // Insert user registration data
                UserRegistrationDataTable.batchInsert(archetypeUsers) { (id, name, _) ->
                    this[UserRegistrationDataTable.id] = id
                    this[UserRegistrationDataTable.publicKey] = "key_$id"
                }

                // Create profiles with location and keywords
                val profilesToInsert = archetypeUsers.map { (id, _, _) ->
                    // Random location within 5km of Paris center
                    val randomAngle = Random.Default.nextDouble(0.0, 2 * Math.PI)
                    val randomRadius = Random.Default.nextDouble(0.0, 5000.0)
                    val latOffset = randomRadius * Math.cos(randomAngle) / 111320.0
                    val lonOffset = randomRadius * Math.sin(randomAngle) / (40075000.0 * Math.cos(
                        Math.toRadians(parisCenterLat)
                    ) / 360)

                    // Use archetype's profile keyword weights with slight variation
                    val profileKeywords = archetype.profileKeywordWeights.mapValues { (_, weight) ->
                        // Add small random variation (±0.1) to make users unique but similar
                        weight + Random.Default.nextDouble(-0.1, 0.5)
                    }

                    Triple(
                        id,
                        Point(parisCenterLat + latOffset, parisCenterLon + lonOffset)
                            .also { p -> p.srid = 4326 },
                        profileKeywords
                    )
                }

                UserProfilesTable.batchInsert(
                    profilesToInsert,
                    shouldReturnGeneratedValues = false
                ) { (id, location, keywords) ->
                    this[UserProfilesTable.userId] = id
                    val uniqueName = archetypeUsers.find { it.first == id }?.second ?: "User_$id"
                    this[UserProfilesTable.name] = uniqueName
                    this[UserProfilesTable.location] = location
                    this[UserProfilesTable.profileKeywordDataMap] = keywords
                }

                // Build list of user_attribute links
                val attributesToInsert = mutableListOf<Triple<String, String, UserAttributeType>>()
                archetypeUsers.forEach { (userId, _, _) ->
                    archetype.providingAttributes.forEach { attrName ->
                        val normalizedAttr = attrName.normalizeAttributeForDBProcessing()
                        attributesToInsert.add(
                            Triple(userId, normalizedAttr, UserAttributeType.PROVIDING)
                        )
                    }

                    archetype.seekingAttributes.forEach { attrName ->
                        val normalizedAttr = attrName.normalizeAttributeForDBProcessing()
                        attributesToInsert.add(
                            Triple(userId, normalizedAttr, UserAttributeType.SEEKING)
                        )
                    }
                }

                // Batch insert user_attribute links (attributes already exist)
                UserAttributesTable.batchInsert(
                    attributesToInsert,
                    ignore = false,
                    shouldReturnGeneratedValues = false
                ) { (userId, attrId, type) ->
                    this[UserAttributesTable.userId] = userId
                    this[UserAttributesTable.attributeId] = attrId
                    this[UserAttributesTable.type] = type
                    this[UserAttributesTable.relevancy] =
                        Random.Default.nextDouble(0.7, 1.0).toBigDecimal()
                }

                println("Generated ${archetypeUsers.size} users with ${attributesToInsert.size} total attribute links")
            }
        }

        // Update semantic profiles
        println("\n=== Updating semantic profiles for all users ===")
        coroutineScope {
            allUsers.forEach { (userId, _, _) ->
                runBlocking {
                    profilesDao.updateSemanticProfile(userId, UserAttributeType.SEEKING)
                    profilesDao.updateSemanticProfile(userId, UserAttributeType.PROVIDING)
                    profilesDao.updateSemanticProfile(userId, UserAttributeType.PROFILE)
                }
            }
        }

        // Test similarity matching for one user from each archetype
        println("\n=== Testing similarity matching for each archetype ===")
        coroutineScope {
            archetypes.forEach { archetype ->
                // Find first user of this archetype
                val testUser = allUsers.find { (userId, _, _) ->
                    userToArchetypeMap[userId] == archetype.name
                }

                if (testUser != null) {
                    runBlocking {
                        println("\n" + "=".repeat(80))
                        println("TESTING: ${archetype.name} (${archetype.description})")
                        println("=".repeat(80))
                        findAndAnalyzeSimilarProfiles(testUser.first, userToArchetypeMap)
                    }
                }
            }
        }

        // Generate summary statistics
        generateMatchingStatistics(allUsers, userToArchetypeMap)
    }

    private suspend fun findAndAnalyzeSimilarProfiles(
        userId: String,
        userToArchetypeMap: Map<String, String>
    ) {
        DatabaseFactory.dbQuery {
            val profilesDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

            val currentUserProfile = profilesDao.getProfile(userId)
            if (currentUserProfile == null) {
                println("Could not find profile for user: $userId")
                return@dbQuery
            }

            val currentArchetype = userToArchetypeMap[userId] ?: "Unknown"

            // Get similar profiles (those with similar PROVIDING attributes)
            val similarProfiles = profilesDao.getSimilarProfiles(userId)

            // Get helpful profiles (those who PROVIDE what this user SEEKS)
            val helpfulProfiles = profilesDao.getHelpfulProfiles(userId)

            // Fetch attribute text mappings
            val allProfiles = listOf(currentUserProfile) +
                    similarProfiles.map { it.profile } +
                    helpfulProfiles.map { it.profile }
            val allAttributeIds = allProfiles.flatMap { it.attributes }.map { it.attributeId }

            val attributeIdToTextMap = AttributesMasterTable
                .select(
                    AttributesMasterTable.attributeNameKey,
                    AttributesMasterTable.customUserAttrText
                )
                .where { AttributesMasterTable.attributeNameKey inList allAttributeIds.toList() }
                .associate {
                    it[AttributesMasterTable.attributeNameKey] to it[AttributesMasterTable.customUserAttrText]
                }

            // Log current user
            logUserProfileDetails(
                currentUserProfile,
                "Test User ($currentArchetype)",
                attributeIdToTextMap
            )

            // Analyze similar profiles
            println("\n--- SIMILAR PROFILES (Similar PROVIDING attributes) ---")
            analyzeSimilarityResults(
                similarProfiles,
                userToArchetypeMap,
                currentArchetype,
                attributeIdToTextMap
            )

            // Analyze helpful profiles
            println("\n--- HELPFUL PROFILES (Their PROVIDING matches my SEEKING) ---")
            analyzeSimilarityResults(
                helpfulProfiles,
                userToArchetypeMap,
                currentArchetype,
                attributeIdToTextMap
            )
        }
    }

    private fun analyzeSimilarityResults(
        profiles: List<org.barter.features.profile.model.UserProfileWithDistance>,
        userToArchetypeMap: Map<String, String>,
        currentArchetype: String,
        attributeMap: Map<String, String?>
    ) {
        if (profiles.isEmpty()) {
            println("No matching profiles found.")
            return
        }

        // Group results by archetype
        val groupedByArchetype = profiles.groupBy {
            userToArchetypeMap[it.profile.userId] ?: "Unknown"
        }

        // Calculate statistics
        val sameArchetypeCount = groupedByArchetype[currentArchetype]?.size ?: 0
        val totalCount = profiles.size
        val sameArchetypePercentage = if (totalCount > 0) {
            (sameArchetypeCount.toDouble() / totalCount * 100)
        } else 0.0

        println("Found $totalCount matching profiles:")
        println(
            "  - Same archetype ($currentArchetype): $sameArchetypeCount (${
                String.format(
                    "%.1f",
                    sameArchetypePercentage
                )
            }%)"
        )

        groupedByArchetype.forEach { (archetype, profileList) ->
            val avgScore = profileList.map { it.distanceKm }.average()
            println(
                "  - $archetype: ${profileList.size} matches (avg score: ${
                    String.format(
                        "%.3f",
                        avgScore
                    )
                })"
            )
        }

        // Show top 5 matches
        println("\nTop 5 Matches:")
        profiles.take(5).forEachIndexed { index, profileWithDistance ->
            val archetype = userToArchetypeMap[profileWithDistance.profile.userId] ?: "Unknown"
            val matchIndicator = if (archetype == currentArchetype) "✓ SAME" else "✗ DIFF"
            println(
                "\n${index + 1}. [$matchIndicator] $archetype (Score: ${
                    String.format(
                        "%.3f",
                        profileWithDistance.distanceKm
                    )
                })"
            )
            logUserProfileDetails(profileWithDistance.profile, "", attributeMap, compact = true)
        }
    }

    private suspend fun generateMatchingStatistics(
        allUsers: List<Triple<String, String, String>>,
        userToArchetypeMap: Map<String, String>
    ) {
        println("\n" + "=".repeat(80))
        println("MATCHING STATISTICS SUMMARY")
        println("=".repeat(80))

        val profilesDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
        val stats = mutableMapOf<String, MutableMap<String, Int>>()

        // Sample 2 users from each archetype for statistics
        archetypes.forEach { archetype ->
            val archetypeUsers = allUsers.filter { (userId, _, _) ->
                userToArchetypeMap[userId] == archetype.name
            }.take(2)

            archetypeUsers.forEach { (userId, _, _) ->
                runBlocking {
                    val similarProfiles = profilesDao.getSimilarProfiles(userId)
                    val currentArchetype = userToArchetypeMap[userId] ?: "Unknown"

                    if (currentArchetype !in stats) {
                        stats[currentArchetype] = mutableMapOf()
                    }

                    // Count matches by archetype
                    similarProfiles.take(10).forEach { profile ->
                        val matchArchetype = userToArchetypeMap[profile.profile.userId] ?: "Unknown"
                        stats[currentArchetype]!![matchArchetype] =
                            stats[currentArchetype]!!.getOrDefault(matchArchetype, 0) + 1
                    }
                }
            }
        }

        // Print statistics
        println("\nArchetype Matching Matrix (Top 10 matches per user):")
        println("Rows = Test User Archetype, Columns = Matched Archetype\n")

        stats.forEach { (sourceArchetype, matches) ->
            println("$sourceArchetype:")
            matches.entries.sortedByDescending { it.value }.forEach { (targetArchetype, count) ->
                val percentage = count.toDouble() / matches.values.sum() * 100
                println(
                    "  → $targetArchetype: $count matches (${
                        String.format(
                            "%.1f",
                            percentage
                        )
                    }%)"
                )
            }
            println()
        }
    }

    private fun logUserProfileDetails(
        profile: UserProfile,
        header: String,
        attributeMap: Map<String, String?>,
        compact: Boolean = false
    ) {
        if (header.isNotEmpty()) {
            println("\n----- $header -----")
        }
        if (!compact) {
            println("User ID: ${profile.userId}")
        }

        val haves = profile.attributes
            .filter { UserAttributeType.entries[it.type] == UserAttributeType.PROVIDING }
            .joinToString(", ") {
                val attrText = attributeMap[it.attributeId] ?: "Unknown (${it.attributeId})"
                if (compact) attrText else "$attrText (${String.format("%.2f", it.relevancy)})"
            }

        val needs = profile.attributes
            .filter { UserAttributeType.entries[it.type] == UserAttributeType.SEEKING }
            .joinToString(", ") {
                val attrText = attributeMap[it.attributeId] ?: "Unknown (${it.attributeId})"
                if (compact) attrText else "$attrText (${String.format("%.2f", it.relevancy)})"
            }

        val profileKeywords = profile.profileKeywordDataMap?.entries
            ?.sortedByDescending { it.value }
            ?.joinToString(", ") { "${it.key.take(30)}: ${String.format("%.2f", it.value)}" }

        if (compact) {
            println("  Provides: ${haves.take(100)}${if (haves.length > 100) "..." else ""}")
            println("  Seeks: ${needs.take(100)}${if (needs.length > 100) "..." else ""}")
        } else {
            println("  Haves: ${haves.ifEmpty { "None" }}")
            println("  Needs: ${needs.ifEmpty { "None" }}")
            println("  Profile Keywords: ${profileKeywords?.ifEmpty { "None" }}")
        }
    }
}
