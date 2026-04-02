package app.bartering.features.profile.routes

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.model.UserRegistrationDataDto
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.profile.model.UserProfile
import app.bartering.features.profile.model.UserProfileUpdateRequest
import app.bartering.features.profile.model.UserConsentUpdateRequest
import app.bartering.features.profile.model.UserProfileExtended
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.reviews.service.LocationPatternDetectionService
import app.bartering.features.reviews.dao.ReputationDao
import app.bartering.features.federation.model.*
import app.bartering.features.profile.model.UserAttributeDto
import app.bartering.features.analytics.service.UserDailyActivityStatsService
import app.bartering.features.profile.service.GdprDataExportService
import io.ktor.client.call.body
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import kotlin.getValue

private val log = LoggerFactory.getLogger("ProfileRoutes")
private val analyticsRecordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun Route.getProfilesNearbyRoute() {

    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/profiles/nearby") {

        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            // Error response has already been sent by verifyRequestSignature
            return@get
        }

        val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
        val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
        val radius = call.request.queryParameters["radius"]?.toDoubleOrNull()
        val excludeUserId =
            call.request.queryParameters["excludeUserId"] // Optional: exclude this user from results

        val hasLocationConsent = userProfileDao.hasLocationConsent(authenticatedUserId)
        if (lat != null && !hasLocationConsent) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "Location consent is required for nearby search")
            )
            return@get
        }

        if (lat == null || lon == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                "Missing or invalid 'lat' and 'lon' query parameters."
            )
            return@get
        }

        val allProfiles =
            userProfileDao.getNearbyProfiles(lat, lon, radius ?: 10000.0, excludeUserId)

        val sortedProfiles = allProfiles.sortedBy { it.distanceKm }.take(20)

        log.debug("Sending {} nearby profiles", sortedProfiles.size)

        call.respond(sortedProfiles)
    }
}

fun Route.getProfileInfoRoute() {

    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    // Route to get the current user's profile
    post("/api/v1/profile-info") {
        val userId = call.receive<String>()

        val profile = userProfileDao.getProfile(userId)
        if (profile != null) {
            call.respond(profile)
        } else {
            call.respond(HttpStatusCode.NotFound, "Profile not found")
        }
    }

}

fun Route.getExtendedProfileInfoRoute() {

    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val reputationDao: ReputationDao by inject(ReputationDao::class.java)

    // Route to get the current user's profile wrapped in UserProfileWithDistance
    post("/api/v1/profile-info-extended") {
        val userId = call.receive<String>()

        val profile = userProfileDao.getProfile(userId)
        if (profile != null) {
            val reputation = reputationDao.getReputation(userId)
            val badges = reputationDao.getUserBadges(userId)

            call.respond(
                UserProfileExtended(
                    profile = profile,
                    distanceKm = 0.0,
                    matchRelevancyScore = null,
                    averageRating = reputation?.averageRating,
                    totalReviews = reputation?.totalReviews,
                    badges = badges.ifEmpty { null }
                )
            )
        } else {
            call.respond(HttpStatusCode.NotFound, "Profile not found")
        }
    }

}

fun Route.createProfileRoute() {
    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    post("/api/v1/profile-create") {
        val user = call.receive<UserRegistrationDataDto>()
        userProfileDao.createProfile(user)
        log.info("User inserted into db: {}", user.id)
        call.respond(HttpStatusCode.OK, "")
    }

}

fun Route.updateProfileRoute() {

    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val locationService: LocationPatternDetectionService by inject(LocationPatternDetectionService::class.java)
    val userDailyActivityStatsService: UserDailyActivityStatsService by inject(UserDailyActivityStatsService::class.java)

    // Route to update the current user's profile
    post("/api/v1/profile-update") {

        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            // Error response has already been sent by verifyRequestSignature
            return@post
        }

        try {
            val request = Json.decodeFromString<UserProfile>(requestBody)

            // Verify that the authenticated user matches the user being updated
            if (authenticatedUserId != request.userId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    "You are not authorized to update this profile."
                )
            }

            // Get current profile to check if location is changing
            val currentProfile = userProfileDao.getProfile(request.userId)
            val oldLatitude = currentProfile?.latitude
            val oldLongitude = currentProfile?.longitude

            // Copy to local variables to avoid smart cast issues with mutable properties
            val newLatitude = request.latitude
            val newLongitude = request.longitude

            val userName = userProfileDao.updateProfile(
                request.userId,
                UserProfileUpdateRequest(
                    name = request.name,
                    latitude = newLatitude,
                    longitude = newLongitude,
                    attributes = request.attributes,
                    profileKeywordDataMap = request.profileKeywordDataMap,
                    selfDescription = request.selfDescription,
                    accountType = request.accountType,
                    profileAvatarIcon = request.profileAvatarIcon,
                    workReferenceImageUrls = request.workReferenceImageUrls,
                    preferredLanguage = request.preferredLanguage
                )
            )

            // Track location change only when user has granted location consent
            val hasLocationConsent = userProfileDao.hasLocationConsent(request.userId)
            if (hasLocationConsent && newLatitude != null && newLongitude != null) {
                val locationChanged = (oldLatitude != newLatitude || oldLongitude != newLongitude)

                if (locationChanged) {
                    locationService.trackLocationChange(
                        userId = request.userId,
                        oldLatitude = oldLatitude,
                        oldLongitude = oldLongitude,
                        newLatitude = newLatitude,
                        newLongitude = newLongitude
                    )
                }
            }

            userDailyActivityStatsService.recordProfileUpdate(request.userId)
            userDailyActivityStatsService.recordSuccessfulAction(request.userId)
            call.respond(userName)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.BadRequest, "Invalid request body: ${e.message}")
        }
    }

}

fun Route.updateUserConsentRoute() {

    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/profile-consent-update") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<UserConsentUpdateRequest>(requestBody)

            if (authenticatedUserId != request.userId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    "You are not authorized to update consent for this profile."
                )
            }

            userProfileDao.updateProfile(
                request.userId,
                UserProfileUpdateRequest(
                    locationConsent = request.locationConsent,
                    aiProcessingConsent = request.aiProcessingConsent,
                    analyticsCookiesConsent = request.analyticsCookiesConsent,
                    federationConsent = request.federationConsent,
                    privacyPolicyVersion = request.privacyPolicyVersion
                )
            )

            call.respond(HttpStatusCode.OK, mapOf("success" to true))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.BadRequest, "Invalid request body: ${e.message}")
        }
    }
}

fun Route.searchProfilesByKeywordRoute() {

    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val federationService: app.bartering.features.federation.service.FederationService by inject(
        app.bartering.features.federation.service.FederationService::class.java
    )
    val federationDao: app.bartering.features.federation.dao.FederationDao by inject(
        app.bartering.features.federation.dao.FederationDao::class.java
    )
    val federatedUserDao: app.bartering.features.federation.dao.FederatedUserDao by inject(
        app.bartering.features.federation.dao.FederatedUserDao::class.java
    )

    // Route to search for user profiles by keyword
    get("/api/v1/profiles/search") {

        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            // Error response has already been sent by verifyRequestSignature
            return@get
        }

        val hasAiProcessingConsent = userProfileDao.hasAiProcessingConsent(authenticatedUserId)

        // Get search text from query parameters
        val searchText = call.request.queryParameters["q"]
            ?: call.request.queryParameters["query"]
            ?: call.request.queryParameters["searchText"]

        if (searchText.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing required parameter 'q', 'query', or 'searchText'")
            )
            return@get
        }

        // Optional location parameters for filtering
        val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
        val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
        val radius = call.request.queryParameters["radius"]?.toDoubleOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val userId = call.request.queryParameters["userId"] ?: ""
        val customWeight = call.request.queryParameters["weight"]?.toIntOrNull() ?: 50
        
        // Optional filters for seeking/offering attributes
        val seeking = call.request.queryParameters["seeking"]?.toBoolean()
        val offering = call.request.queryParameters["offering"]?.toBoolean()

        // Optional: enable/disable federated search fallback
        val enableFederatedSearch = call.request.queryParameters["federated"]?.toBoolean() ?: true
        val federatedMinResults = call.request.queryParameters["federatedMinResults"]?.toIntOrNull() ?: 3

        // Validate limit
        if (limit !in 1..100) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Limit must be between 1 and 100")
            )
            return@get
        }

        // Validate custom weight
        if (customWeight !in 10..100) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Weight must be between 10 and 100")
            )
            return@get
        }

        // Validate location parameters if provided
        if ((lat != null && lon == null) || (lat == null && lon != null)) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Both 'lat' and 'lon' must be provided together")
            )
            return@get
        }

        if (lat != null) {
            val hasLocationConsent = userProfileDao.hasLocationConsent(authenticatedUserId)
            if (!hasLocationConsent) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Location consent is required for geo-filtered search")
                )
                return@get
            }
        }

        val searchStartedAt = System.currentTimeMillis()

        try {
            // First: Search local profiles
            var matchingProfiles = if (hasAiProcessingConsent) {
                userProfileDao.searchProfilesByKeyword(
                    userId = userId,
                    searchText = searchText,
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radius,
                    limit = limit,
                    customWeight = customWeight,
                    seeking = seeking,
                    offering = offering
                )
            } else {
                // Privacy fallback: no AI ranking, randomized attribute-based profiles
                userProfileDao.getSimilarProfiles(authenticatedUserId, lat, lon, radius).shuffled().take(limit)
            }

            log.info("Local search returned {} profiles for query: '{}'", 
                matchingProfiles.size, searchText)

            // Second: If few results and federated search enabled, query trusted servers
            if (enableFederatedSearch && matchingProfiles.size < federatedMinResults) {
                val federatedResults = searchFederatedProfiles(
                    query = searchText,
                    limit = limit - matchingProfiles.size,
                    federationService = federationService,
                    federationDao = federationDao,
                    federatedUserDao = federatedUserDao
                )

                if (federatedResults.isNotEmpty()) {
                    log.info("Federated search added {} profiles from remote servers", 
                        federatedResults.size)
                    
                    // Combine local and federated results
                    // Federated profiles already have @serverId suffix from searchFederatedProfiles
                    matchingProfiles = (matchingProfiles + federatedResults).take(limit)
                }
            }

            log.info("Returning {} total profiles for search: '{}' (local + federated)", 
                matchingProfiles.size, searchText)

            val userDailyActivityStatsService: UserDailyActivityStatsService by inject(
                UserDailyActivityStatsService::class.java
            )
            val elapsedMs = System.currentTimeMillis() - searchStartedAt
            analyticsRecordingScope.launch {
                try {
                    userDailyActivityStatsService.recordSearchedKeywordWithResponseTime(
                        authenticatedUserId,
                        searchText,
                        elapsedMs
                    )
                } catch (e: Exception) {
                    log.debug("Async keyword analytics recording failed", e)
                }
            }

            call.respond(HttpStatusCode.OK, matchingProfiles)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "An error occurred while searching profiles")
            )
        }
    }

}

/**
 * Searches for profiles on federated (trusted) servers when local results are insufficient.
 * 
 * @param query The search text/keywords
 * @param limit Maximum number of results to return from all servers combined
 * @param federationService Service for signing requests to remote servers
 * @param federationDao DAO for accessing federated server information
 * @param federatedUserDao DAO for caching federated user profiles
 * @return List of profiles from remote servers with their origin marked
 */
private suspend fun searchFederatedProfiles(
    query: String,
    limit: Int,
    federationService: app.bartering.features.federation.service.FederationService,
    federationDao: app.bartering.features.federation.dao.FederationDao,
    federatedUserDao: app.bartering.features.federation.dao.FederatedUserDao
): List<app.bartering.features.profile.model.UserProfileExtended> {
    val results = mutableListOf<app.bartering.features.profile.model.UserProfileExtended>()
    
    try {
        // Get trusted (FULL or PARTIAL trust) federated servers
        val trustedServers = federationDao.listFederatedServers()
            .filter { it.isActive && it.trustLevel != TrustLevel.BLOCKED }
            .filter { it.scopePermissions.users } // Only servers that allow user search
        
        if (trustedServers.isEmpty()) {
            return emptyList()
        }
        
        // Search each server (with limit per server)
        val perServerLimit = (limit / trustedServers.size).coerceAtLeast(5)
        
        for (server in trustedServers) {
            try {
                val remoteProfiles = searchSingleFederatedServer(
                    server = server,
                    query = query,
                    limit = perServerLimit,
                    federationService = federationService
                )
                
                // Convert FederatedUserProfile to UserProfileWithDistance
                remoteProfiles.forEach { remoteProfile ->
                    val federatedUserId = "${remoteProfile.userId}@${server.serverId}"
                    
                    // Cache the federated user with their public key for chat
                    try {
                        federatedUserDao.upsertFederatedUser(
                            remoteUserId = remoteProfile.userId,
                            originServerId = server.serverId,
                            federatedUserId = federatedUserId,
                            profileData = remoteProfile,
                            publicKey = remoteProfile.publicKey,
                            expiresAt = java.time.Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS)
                        )
                    } catch (e: Exception) {
                        log.debug("Failed to cache federated user {}: {}", federatedUserId, e.message)
                    }
                    
                    val profile = UserProfile(
                        userId = federatedUserId, // Mark as federated
                        name = remoteProfile.name ?: "Unknown",
                        latitude = remoteProfile.location?.lat,
                        longitude = remoteProfile.location?.lon,
                        attributes = remoteProfile.attributes?.map { attr ->
                            UserAttributeDto(
                                attributeId = attr.attributeId,
                                type = attr.type, // Preserve type from federated source
                                relevancy = attr.relevancy,
                                description = null,
                                uiStyleHint = attr.uiStyleHint
                            )
                        } ?: emptyList(),
                        profileKeywordDataMap = null,
                        activePostingIds = emptyList(),
                        lastOnlineAt = remoteProfile.lastOnline?.toEpochMilli()
                    )
                    
                    results.add(
                        app.bartering.features.profile.model.UserProfileExtended(
                            profile = profile,
                            distanceKm = -1.0, // Unknown distance for federated
                            matchRelevancyScore = 0.5, // Default score
                            averageRating = null,
                            totalReviews = null,
                            badges = null
                        )
                    )
                }
                
                if (results.size >= limit) break
            } catch (e: Exception) {
                // Log but continue to next server
                log.warn("Failed to search server {}: {}", server.serverId, e.message)
            }
        }
    } catch (e: Exception) {
        log.warn("Federated search failed: {}", e.message)
    }
    
    return results.take(limit)
}

/**
 * Searches a single federated server for profiles matching the query.
 */
private suspend fun searchSingleFederatedServer(
    server: FederatedServer,
    query: String,
    limit: Int,
    federationService: app.bartering.features.federation.service.FederationService
): List<FederatedUserProfile> {
    
    val timestamp = System.currentTimeMillis()
    val localIdentity = federationService.getLocalServerIdentity()
        ?: throw IllegalStateException("Local server not initialized")
    
    // Build signature data: serverId|query|limit|timestamp
    val signatureData = "${localIdentity.serverId}|$query|$limit|$timestamp"
    val signature = federationService.signWithLocalKey(signatureData)
    
    // Debug logging
    log.debug("Federation search signature: serverId=${localIdentity.serverId}, signatureData='$signatureData', signature='$signature'")
    
    // Build target URL - properly URL-encode query and signature
    val targetUrl = "${server.serverUrl}/federation/v1/profiles/search" +
        "?serverId=${localIdentity.serverId}" +
        "&q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
        "&limit=$limit" +
        "&timestamp=$timestamp" +
        "&signature=${java.net.URLEncoder.encode(signature, "UTF-8")}"

    // Make HTTP request - use Ktor client with JSON support
    val client = HttpClient {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    val response: FederationApiResponse<ProfileSearchResponse> = client.get(targetUrl).body()
    
    client.close()
    
    return if (response.success && response.data != null) {
        response.data.users
    } else {
        emptyList()
    }
}

fun Route.similarProfilesRoute() {
    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    get("/api/v1/similar-profiles") {
        // Get userId from query parameters
        val userId = call.request.queryParameters["userId"]

        if (userId.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing required parameter 'userId'")
            )
            return@get
        }

        // Optional location parameters for geo-filtering
        val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
        val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
        val radius = call.request.queryParameters["radius"]?.toDoubleOrNull()
        
        // Validate that both lat and lon are provided together
        if ((lat != null && lon == null) || (lat == null && lon != null)) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Both 'lat' and 'lon' must be provided together")
            )
            return@get
        }

        if (lat != null) {
            val hasLocationConsent = userProfileDao.hasLocationConsent(userId)
            if (!hasLocationConsent) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Location consent is required for geo-filtered similar profiles")
                )
                return@get
            }
        }

        val profiles = userProfileDao.getSimilarProfiles(userId, lat, lon, radius)
        
        log.debug("Returning {} similar profiles for user {} (location filter: {})", 
            profiles.size, userId, if (lat != null) "enabled" else "disabled")
        
        call.respond(HttpStatusCode.OK, profiles)
    }

}

fun Route.complementaryProfilesRoute() {
    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    get("/api/v1/complementary-profiles") {
        // Get userId from query parameters
        val userId = call.request.queryParameters["userId"]

        if (userId.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing required parameter 'userId'")
            )
            return@get
        }

        // Optional location parameters for geo-filtering
        val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
        val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
        val radius = call.request.queryParameters["radius"]?.toDoubleOrNull()

        // Validate that both lat and lon are provided together
        if ((lat != null && lon == null) || (lat == null && lon != null)) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Both 'lat' and 'lon' must be provided together")
            )
            return@get
        }

        if (lat != null) {
            val hasLocationConsent = userProfileDao.hasLocationConsent(userId)
            if (!hasLocationConsent) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Location consent is required for geo-filtered complementary profiles")
                )
                return@get
            }
        }

        val profiles = userProfileDao.getHelpfulProfiles(userId, lat, lon, radius)

        log.debug("Returning {} complementary profiles for user {} (location filter: {})",
            profiles.size, userId, if (lat != null) "enabled" else "disabled")

        call.respond(HttpStatusCode.OK, profiles)
    }
}

fun Route.requestGdprDataExportRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val exportService: GdprDataExportService by inject(GdprDataExportService::class.java)

    post("/api/v1/profile/export-data") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@post
        }

        val result = try {
            exportService.exportAndSendToUser(authenticatedUserId)
        } catch (e: Exception) {
            log.error("Failed to process GDPR data export for user {}", authenticatedUserId, e)
            app.bartering.features.profile.service.ExportResult(
                success = false,
                message = "Failed to process data export request"
            )
        }

        val responseCode = if (result.success) HttpStatusCode.Accepted else HttpStatusCode.BadRequest
        call.respond(responseCode,
            app.bartering.features.profile.service.ExportResult(
                success = true,
                message = result.message
            ))
    }
}
