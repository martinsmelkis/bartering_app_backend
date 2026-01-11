package org.barter.features.relationships.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.authentication.utils.verifyRequestSignature
import org.barter.features.relationships.dao.UserRelationshipsDaoImpl
import org.barter.features.relationships.dao.UserReportsDaoImpl
import org.barter.features.relationships.model.*
import org.koin.java.KoinJavaComponent.inject

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
            val reportReason = ReportReason.fromString(request.reportReason)
            if (reportReason == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid report reason: ${request.reportReason}")
                )
            }

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

/**
 * Get report statistics for a user (public endpoint for moderation)
 */
fun Route.getUserReportStatsRoute() {
    val reportsDao: UserReportsDaoImpl by inject(UserReportsDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reports/stats/{userId}") {
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
