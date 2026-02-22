package app.bartering.features.ai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.response.* 
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.attributes.dao.AttributesDaoImpl
import app.bartering.features.attributes.dao.UserAttributesDaoImpl
import app.bartering.features.attributes.model.AttributesRequest
import app.bartering.features.attributes.model.UserAttributeType
import app.bartering.features.categories.dao.CategoriesDaoImpl
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.profile.db.UserProfilesTable
import app.bartering.features.profile.model.OnboardingDataRequest
import app.bartering.features.profile.model.UserProfileUpdateRequest
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.notifications.service.MatchNotificationService
import app.bartering.localization.Localization
import app.bartering.utils.ValidationUtils
import org.jetbrains.exposed.sql.select
import org.koin.java.KoinJavaComponent.inject
import java.math.BigDecimal
import java.util.Locale
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

fun Route.getInterestsFromOnboardingData() {

    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/ai/parse-onboarding") {
        // --- Authentication and Data Reception ---
        val userId = call.request.headers["X-User-ID"]

        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            // Error response has already been sent by the helper
            return@post
        }
        val request = Json.decodeFromString<OnboardingDataRequest>(requestBody)

        if (userId != request.userId) {
            return@post call.respond(HttpStatusCode.Forbidden, "User ID mismatch.")
        }

        // Validate input size to prevent DoS
        if (!ValidationUtils.validateMapSize(request.onboardingKeyNamesToWeights)) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                "Too many attributes. Maximum allowed: ${ValidationUtils.MAX_ATTRIBUTES_PER_REQUEST}"
            )
        }

        val attributesDao: AttributesDaoImpl by inject(AttributesDaoImpl::class.java)
        val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
        val categoriesDao: CategoriesDaoImpl by inject(CategoriesDaoImpl::class.java)

        // Fetch main categories from the database, ordered by ID to maintain consistent order
        val mainCategories = categoriesDao.findAllMainCategoriesWithDescriptions()
            .sortedBy { it.id } // Sort by ID to maintain the order they were inserted

        // Use LinkedHashMap to preserve insertion order when passing to updateProfile and parseInterestSuggestionsFromOnboardingData
        val extendedMap: LinkedHashMap<String, Double> = linkedMapOf()
        request.onboardingKeyNamesToWeights.values.onEachIndexed { index, value ->
            try {
                // Use the category description from the database (maintains order by ID)
                if (index < mainCategories.size) {
                    extendedMap[mainCategories[index].description] = value
                } else {
                    application.log.warn("Onboarding index $index exceeds available categories (${mainCategories.size})")
                }
            } catch (t: Throwable) {
                application.log.error("Failed to process onboarding keyword at index $index", t)
            }
        }
        
        // --- AI Processing ---
        userProfileDao.updateProfile(request.userId, UserProfileUpdateRequest(
            profileKeywordDataMap = extendedMap))

        userProfileDao.updateSemanticProfile(userId, UserAttributeType.PROFILE)

        // TODO also give interest/interaction/popularity/search frequency a weight in suggestions
        val parsedOfferingsSuggestions = attributesDao.parseInterestSuggestionsFromOnboardingData(
            extendedMap,
            userId = userId,
            limit = 26
        )

        // --- Return a Success Response ---
        call.respond(HttpStatusCode.OK, parsedOfferingsSuggestions)
    }
}

fun Route.getOfferingsFromInterestsData() {

    val attributesDao: AttributesDaoImpl by inject(AttributesDaoImpl::class.java)
    val userAttributesDao: UserAttributesDaoImpl by inject(UserAttributesDaoImpl::class.java)
    val usersDB: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/ai/parse-interests") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, usersDB)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val requestObj = Json.decodeFromString<AttributesRequest>(requestBody)

            if (authenticatedUserId != requestObj.userId) {
                return@post call.respond(HttpStatusCode.Forbidden, "You are not authorized to access this resource.")
            }

            // Validate input size to prevent DoS
            if (!ValidationUtils.validateMapSize(requestObj.attributesRelevancyData)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Too many attributes. Maximum allowed: ${ValidationUtils.MAX_ATTRIBUTES_PER_REQUEST}"
                )
            }

            // --- Persist the Parsed Offerings to the Database ---
            // Get user's preferred language for attribute name translation
            val userProfileRow = dbQuery {
                UserProfilesTable
                    .select(UserProfilesTable.preferredLanguage)
                    .where { UserProfilesTable.userId eq requestObj.userId }
                    .singleOrNull()
            }
            val userPreferredLanguage = userProfileRow?.get(UserProfilesTable.preferredLanguage) ?: "en"
            
            // Only use Accept-Language header translation if user's preferred language is English
            val effectiveLanguage = if (userPreferredLanguage == "en") {
                call.request.headers["Accept-Language"]?.split(",")?.firstOrNull()?.split(";")?.firstOrNull()?.trim() ?: "en"
            } else {
                userPreferredLanguage
            }

            try {
                updateUserAttributesFromMap(
                    userId = requestObj.userId,
                    attributesDao = attributesDao,
                    userAttributesDao = userAttributesDao,
                    attributesMap = requestObj.attributesRelevancyData,
                    type = UserAttributeType.SEEKING,
                    application = application,
                    language = effectiveLanguage
                )
            } catch (e: Exception) {
                application.log.error("Failed to save parsed offerings for user ${requestObj.userId}", e)
                return@post call.respond(HttpStatusCode.InternalServerError, "Could not save offerings to profile.")
            }

            val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
            userProfileDao.updateSemanticProfile(requestObj.userId, UserAttributeType.SEEKING)

            // Check for matching postings for each new SEEKING attribute
            val matchNotificationService: MatchNotificationService by inject(MatchNotificationService::class.java)
            for ((attributeKey, _) in requestObj.attributesRelevancyData) {
                try {
                    // 1. Check existing postings that match this SEEKING attribute (user gets notified)
                    matchNotificationService.checkAttributeAgainstPostings(requestObj.userId, attributeKey)
                    
                    // 2. Check existing OFFERING postings and notify their owners
                    matchNotificationService.checkSeekingAgainstOfferingPostings(requestObj.userId, attributeKey)

                    // 3. Profile attribute matching: notify nearby users with PROVIDING this attribute
                    matchNotificationService.checkUserAttributeAgainstOtherUserProfiles(
                        requestObj.userId,
                        attributeKey,
                        UserAttributeType.SEEKING
                    )
                } catch (e: Exception) {
                    application.log.error("Failed to check attribute '$attributeKey' against postings for user ${requestObj.userId}", e)
                    // Continue with other attributes even if one fails
                }
            }

            val parsedInterestSuggestions = attributesDao.getComplementaryInterestSuggestions(
                requestObj.attributesRelevancyData,
                26,
                requestObj.userId
            )

            call.respond(parsedInterestSuggestions)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid JSON format: ${e.message}")
        }
    }

}

fun Route.parseOfferingsAndUpdateProfile() {

    val usersDB: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/ai/parse-offerings") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, usersDB)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val requestObj = Json.decodeFromString<AttributesRequest>(requestBody)

            if (authenticatedUserId != requestObj.userId) {
                return@post call.respond(HttpStatusCode.Forbidden, "You are not authorized to access this resource.")
            }

            // Validate input size to prevent DoS
            if (!ValidationUtils.validateMapSize(requestObj.attributesRelevancyData)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Too many attributes. Maximum allowed: ${ValidationUtils.MAX_ATTRIBUTES_PER_REQUEST}"
                )
            }

            val attributesDao: AttributesDaoImpl by inject(AttributesDaoImpl::class.java)
            val userAttributesDao: UserAttributesDaoImpl by inject(UserAttributesDaoImpl::class.java)

            // --- Persist the Parsed Offerings to the Database ---
            // Get user's preferred language for attribute name translation
            val userProfileRow = dbQuery {
                UserProfilesTable
                    .select(UserProfilesTable.preferredLanguage)
                    .where { UserProfilesTable.userId eq requestObj.userId }
                    .singleOrNull()
            }
            val userPreferredLanguage = userProfileRow?.get(UserProfilesTable.preferredLanguage) ?: "en"
            
            // Only use Accept-Language header translation if user's preferred language is English
            val effectiveLanguage = if (userPreferredLanguage == "en") {
                call.request.headers["Accept-Language"]?.split(",")?.firstOrNull()?.split(";")?.firstOrNull()?.trim() ?: "en"
            } else {
                userPreferredLanguage
            }

            try {
                updateUserAttributesFromMap(
                    userId = requestObj.userId,
                    attributesDao = attributesDao,
                    userAttributesDao = userAttributesDao,
                    attributesMap = requestObj.attributesRelevancyData,
                    type = UserAttributeType.PROVIDING,
                    application = application,
                    language = effectiveLanguage
                )
            } catch (e: Exception) {
                application.log.error("Failed to save parsed offerings for user ${requestObj.userId}", e)
                return@post call.respond(HttpStatusCode.InternalServerError, "Could not save offerings to profile.")
            }

            val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
            userProfileDao.updateSemanticProfile(requestObj.userId, UserAttributeType.PROVIDING)

            // Check for matching postings for each new PROVIDING/OFFERING attribute
            val matchNotificationService: MatchNotificationService by inject(MatchNotificationService::class.java)
            for ((attributeKey, _) in requestObj.attributesRelevancyData) {
                try {
                    // 1. Check existing SEEKING postings and notify their owners
                    matchNotificationService.checkOfferingAgainstSeekingPostings(requestObj.userId, attributeKey)
                    
                    // 2. Profile attribute matching: notify nearby users with SEEKING this attribute
                    matchNotificationService.checkUserAttributeAgainstOtherUserProfiles(
                        requestObj.userId, 
                        attributeKey, 
                        UserAttributeType.PROVIDING
                    )
                } catch (e: Exception) {
                    application.log.error("Failed to check offering attribute '$attributeKey' against postings for user ${requestObj.userId}", e)
                    // Continue with other attributes even if one fails
                }
            }

            call.respond("")

        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid JSON format: ${e.message}")
        }
    }

}

/**
 * A reusable helper function to clear and update a user's attributes of a specific type from a map.
 *
 * IMPORTANT: This function ensures attributes are created BEFORE linking them to users.
 * Each attribute creation happens in its own transaction to avoid batch insert conflicts.
 * 
 * @param language The user's preferred language for attribute name translation (ISO 639-1 code, e.g., "en", "lv")
 */
private suspend fun updateUserAttributesFromMap(
    userId: String,
    attributesDao: AttributesDaoImpl,
    userAttributesDao: UserAttributesDaoImpl,
    attributesMap: Map<String, Double>,
    type: UserAttributeType,
    application: Application,
    language: String = "en"
) {
    // Step 1: Clear previous attributes in a separate transaction
    dbQuery {
        userAttributesDao.deleteUserAttributesByType(userId, type)
    }

    // Step 2: Ensure all attributes exist FIRST (separate transactions for each)
    val validAttributes = mutableListOf<Triple<String, Double, String?>>() // attributeKey, relevancy, translatedDescription (null for English)

    val locale = Locale(language)

    for ((attributeNameKey, relevancy) in attributesMap) {
        try {
            // Validate attribute key
            val sanitizedKey = ValidationUtils.validateAttributeKey(attributeNameKey)
            if (sanitizedKey == null) {
                application.log.warn("Invalid attribute key rejected: $attributeNameKey")
                continue
            }

            // Validate and clamp relevancy value
            val validatedRelevancy = ValidationUtils.validateRelevancy(relevancy)

            // Each findOrCreate gets its own transaction and is committed before continuing
            val attribute = attributesDao.findOrCreate(sanitizedKey)

            if (attribute == null) {
                application.log.warn("Could not find or create attribute for key: $sanitizedKey")
                continue
            }

            // Get translated attribute name from localization files (only for non-English locales)
            // The localization key is stored in the format "attr_<attributeKey>"
            val localizationKey = attribute.localizationKey
            val translatedName = if (language != "en") {
                try {
                    val translation = Localization.getString(localizationKey, locale)
                    // If translation equals the key (not found), use original attribute name as fallback
                    if (translation == localizationKey) attributeNameKey else translation
                } catch (_: Exception) {
                    application.log.debug("No translation found for key: $localizationKey in language: $language")
                    attributeNameKey
                }
            } else {
                null // Don't store description for English locale
            }

            println("@@@@@@@@@@@ Validated attribute exists: ${attribute.attributeNameKey}")
            validAttributes.add(Triple(attribute.attributeNameKey, validatedRelevancy, translatedName))

        } catch (e: Exception) {
            application.log.error(
                "Error creating attribute '$attributeNameKey' for user $userId",
                e
            )
            // Continue to the next attribute even if one fails.
        }
    }

    // Step 3: Now batch insert all user_attribute links (all attributes are guaranteed to exist)
    dbQuery {
        for ((attributeKey, relevancy, translatedDescription) in validAttributes) {
            try {
                val relevancyBigDecimal = try {
                    relevancy.toBigDecimal()
                } catch (_: Throwable) {
                    BigDecimal(0.0)
                }

                // Create the link between the user and this attribute
                // For non-English locales: use translated description if available, fallback to attributeKey
                // For English locale: description is null (not stored)
                val effectiveDescription = when {
                    translatedDescription != null -> translatedDescription // Non-English with translation or fallback
                    language != "en" -> attributeKey // Non-English without translation fallback
                    else -> null // English locale - don't store description
                }

                userAttributesDao.create(
                    userId = userId,
                    attributeId = attributeKey,
                    type = type,
                    relevancy = relevancyBigDecimal,
                    description = effectiveDescription
                )
            } catch (e: Exception) {
                application.log.error("Error linking attribute '$attributeKey' to user $userId", e)
                // Continue to the next attribute even if one fails.
            }
        }
    }
}