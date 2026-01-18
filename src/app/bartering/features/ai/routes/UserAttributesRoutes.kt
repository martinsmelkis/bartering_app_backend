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
import app.bartering.features.profile.model.OnboardingDataRequest
import app.bartering.features.profile.model.UserProfileUpdateRequest
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.notifications.service.MatchNotificationService
import org.koin.java.KoinJavaComponent.inject
import java.math.BigDecimal
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

        val attributesDao: AttributesDaoImpl by inject(AttributesDaoImpl::class.java)
        val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
        val categoriesDao: CategoriesDaoImpl by inject(CategoriesDaoImpl::class.java)

        // Fetch main categories from the database, ordered by ID to maintain consistent order
        val mainCategories = categoriesDao.findAllMainCategoriesWithDescriptions()
            .sortedBy { it.id } // Sort by ID to maintain the order they were inserted

        val extendedMap: HashMap<String, Double> = hashMapOf()
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
            limit = 22
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

            // --- Persist the Parsed Offerings to the Database ---
            try {
                updateUserAttributesFromMap(
                    userId = requestObj.userId,
                    attributesDao = attributesDao,
                    userAttributesDao = userAttributesDao,
                    attributesMap = requestObj.attributesRelevancyData,
                    type = UserAttributeType.SEEKING, // Offerings are what a user is PROVIDING
                    application = application
                )
            } catch (e: Exception) {
                application.log.error("Failed to save parsed offerings for user ${requestObj.userId}", e)
                return@post call.respond(HttpStatusCode.InternalServerError, "Could not save offerings to profile.")
            }

            val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
            userProfileDao.updateSemanticProfile(requestObj.userId, UserAttributeType.SEEKING)

            // Check for matching postings for each new SEEKING attribute
            val matchNotificationService: app.bartering.features.notifications.service.MatchNotificationService by inject(app.bartering.features.notifications.service.MatchNotificationService::class.java)
            for ((attributeKey, _) in requestObj.attributesRelevancyData) {
                try {
                    // 1. Check existing postings that match this SEEKING attribute (user gets notified)
                    matchNotificationService.checkAttributeAgainstPostings(requestObj.userId, attributeKey)
                    
                    // 2. Check existing OFFERING postings and notify their owners
                    matchNotificationService.checkSeekingAgainstOfferingPostings(requestObj.userId, attributeKey)
                } catch (e: Exception) {
                    application.log.error("Failed to check attribute '$attributeKey' against postings for user ${requestObj.userId}", e)
                    // Continue with other attributes even if one fails
                }
            }

            val parsedInterestSuggestions = attributesDao.getComplementaryInterestSuggestions(
                requestObj.attributesRelevancyData,
                22,
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

            val attributesDao: AttributesDaoImpl by inject(AttributesDaoImpl::class.java)
            val userAttributesDao: UserAttributesDaoImpl by inject(UserAttributesDaoImpl::class.java)

            // --- Persist the Parsed Offerings to the Database ---
            try {
                updateUserAttributesFromMap(
                    userId = requestObj.userId,
                    attributesDao = attributesDao,
                    userAttributesDao = userAttributesDao,
                    attributesMap = requestObj.attributesRelevancyData,
                    type = UserAttributeType.PROVIDING, // Offerings are what a user is PROVIDING
                    application = application
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
                    // Check existing SEEKING postings and notify their owners
                    matchNotificationService.checkOfferingAgainstSeekingPostings(requestObj.userId, attributeKey)
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
 */
private suspend fun updateUserAttributesFromMap(
    userId: String,
    attributesDao: AttributesDaoImpl,
    userAttributesDao: UserAttributesDaoImpl,
    attributesMap: Map<String, Double>,
    type: UserAttributeType,
    application: Application
) {
    // Step 1: Clear previous attributes in a separate transaction
    dbQuery {
        userAttributesDao.deleteUserAttributesByType(userId, type)
    }

    // Step 2: Ensure all attributes exist FIRST (separate transactions for each)
    val validAttributes = mutableListOf<Pair<String, Double>>()

    for ((attributeNameKey, relevancy) in attributesMap) {
        try {
            // Each findOrCreate gets its own transaction and is committed before continuing
            val attribute = attributesDao.findOrCreate(attributeNameKey)

            if (attribute == null) {
                application.log.warn("Could not find or create attribute for key: $attributeNameKey")
                continue
            }

            println("@@@@@@@@@@@ Validated attribute exists: ${attribute.attributeNameKey}")
            validAttributes.add(attribute.attributeNameKey to relevancy)

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
        for ((attributeKey, relevancy) in validAttributes) {
            try {
                val relevancyBigDecimal = try {
                    relevancy.toBigDecimal()
                } catch (_: Throwable) {
                    BigDecimal(0.0)
                }

                // Create the link between the user and this attribute
                userAttributesDao.create(
                    userId = userId,
                    attributeId = attributeKey,
                    type = type,
                    relevancy = relevancyBigDecimal,
                    description = ""
                )
            } catch (e: Exception) {
                application.log.error("Error linking attribute '$attributeKey' to user $userId", e)
                // Continue to the next attribute even if one fails.
            }
        }
    }
}