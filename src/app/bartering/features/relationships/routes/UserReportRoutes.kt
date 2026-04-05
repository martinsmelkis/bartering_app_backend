package app.bartering.features.relationships.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.relationships.dao.UserRelationshipsDaoImpl
import app.bartering.features.relationships.dao.UserReportsDaoImpl
import app.bartering.features.relationships.model.*
import org.koin.java.KoinJavaComponent.inject
import kotlinx.serialization.Serializable
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.utils.SecurityUtils

/**
 * Create a user report
 */
fun Route.createUserReportRoute() {
    val reportsDao: UserReportsDaoImpl by inject(UserReportsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val relationshipsDao: UserRelationshipsDaoImpl by inject(UserRelationshipsDaoImpl::class.java)

    post("/api/v1/reports/create") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<UserReportRequest>(requestBody)

            // Verify the authenticated user matches the reporter
            if (authenticatedUserId != request.reporterUserId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only file reports for yourself")
                )
            }

            // Don't allow reporting yourself
            if (request.reporterUserId == request.reportedUserId) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Cannot report yourself")
                )
            }

            // Validate report reason
            val reportReason =
                ReportReason.fromString(request.reportReason) ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid report reason: ${request.reportReason}")
                )

            // Validate context type if provided
            val contextType = request.contextType?.let { ReportContextType.fromString(it) }
            if (request.contextType != null && contextType == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid context type: ${request.contextType}")
                )
            }

            // Check if user has already reported this user
            val alreadyReported = reportsDao.hasReported(
                request.reporterUserId,
                request.reportedUserId
            )
            if (alreadyReported) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "You have already reported this user")
                )
            }

            // Create the report
            val reportId = reportsDao.createReport(
                reporterUserId = request.reporterUserId,
                reportedUserId = request.reportedUserId,
                reportReason = reportReason,
                description = request.description,
                contextType = contextType,
                contextId = request.contextId
            )

            if (reportId != null) {
                // Automatically create a REPORTED relationship
                relationshipsDao.createRelationship(
                    request.reporterUserId,
                    request.reportedUserId,
                    RelationshipType.REPORTED
                )

                call.respond(
                    HttpStatusCode.Created,
                    reportId
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to create report")
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Get user's filed reports
 */
fun Route.getUserReportsRoute() {
    val reportsDao: UserReportsDaoImpl by inject(UserReportsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reports/user/{userId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        // Users can only view their own reports
        if (authenticatedUserId != userId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only view your own reports")
            )
        }

        try {
            val reports = reportsDao.getReportsByReporter(userId)
            call.respond(HttpStatusCode.OK, reports)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve reports")
            )
        }
    }
}

/**
 * Check if user has already reported another user
 */
fun Route.checkHasReportedRoute() {
    val reportsDao: UserReportsDaoImpl by inject(UserReportsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reports/check") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val reporterUserId = call.request.queryParameters["reporterUserId"]
        val reportedUserId = call.request.queryParameters["reportedUserId"]

        if (reporterUserId.isNullOrBlank() || reportedUserId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing required parameters")
            )
        }

        // Users can only check their own reports
        if (authenticatedUserId != reporterUserId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only check your own reports")
            )
        }

        try {
            val hasReported = reportsDao.hasReported(reporterUserId, reportedUserId)
            call.respond(HttpStatusCode.OK, hasReported)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to check report status")
            )
        }
    }
}

private suspend fun requireAdminUser(
    call: io.ktor.server.application.ApplicationCall,
    authDao: AuthenticationDaoImpl,
    userProfileDao: UserProfileDaoImpl
): String? {
    val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
    if (authenticatedUserId == null) {
        return null
    }

    val isAdmin = userProfileDao.isComplianceAdmin(authenticatedUserId)
    if (!isAdmin) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "User is not authorized for moderation endpoints")
        )
        return null
    }

    return authenticatedUserId
}

/**
 * Get report statistics for a user (admin moderation endpoint)
 */
fun Route.getUserReportStatsRoute() {
    val reportsDao: UserReportsDaoImpl by inject(UserReportsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    get("/api/v1/reports/stats/{userId}") {
        requireAdminUser(call, authDao, userProfileDao) ?: return@get

        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        if (!SecurityUtils.isValidUUID(userId)) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid userId parameter")
            )
        }

        try {
            val stats = reportsDao.getUserReportStats(userId)
            call.respond(HttpStatusCode.OK, stats)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve report statistics")
            )
        }
    }
}

@Serializable
data class ModerationReportedUsersResponse(
    val userIds: List<String>
)

/**
 * Get distinct reported user IDs for moderation dashboard (admin moderation endpoint)
 */
fun Route.getModerationReportedUsersRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

    get("/api/v1/reports/moderation/users") {
        requireAdminUser(call, authDao, userProfileDao) ?: return@get

        try {
            val userIds = dbQuery {
                val results = mutableListOf<String>()
                val sql = """
                    SELECT reported_user_id
                    FROM user_reports
                    GROUP BY reported_user_id
                    ORDER BY MAX(reported_at) DESC
                    LIMIT 200
                """.trimIndent()

                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec(sql) { rs ->
                    while (rs.next()) {
                        val userId = rs.getString("reported_user_id")
                        if (!userId.isNullOrBlank()) {
                            results.add(userId)
                        }
                    }
                }
                results
            }

            call.respond(HttpStatusCode.OK, ModerationReportedUsersResponse(userIds = userIds))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve moderation users")
            )
        }
    }
}
