package org.barter.features.authentication.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.authentication.model.DeleteUserRequest
import org.barter.features.authentication.model.DeleteUserResponse
import org.barter.features.authentication.utils.verifyRequestSignature
import org.koin.java.KoinJavaComponent.inject

/**
 * Route for deleting a user and all their associated data.
 * 
 * Requires signature verification to ensure the request comes from the user being deleted.
 * 
 * This endpoint will delete:
 * - User registration data
 * - User profile (cascades via FK)
 * - User attributes (cascades via FK)
 * - User relationships (cascades via FK)
 * - User postings (cascades via FK)
 *   - Posting attributes links (cascades via FK from postings)
 * - Offline messages (handled via sender_id/recipient_id matching)
 * - Encrypted files (handled via sender_id/recipient_id matching)
 */
fun Route.deleteUserRoute() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    delete("/api/v1/authentication/user/{userId}") {
        // --- Authentication using signature verification ---
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            // Error response has already been sent by verifyRequestSignature
            return@delete
        }

        // Get the userId from the path parameter
        val userIdToDelete = call.parameters["userId"]

        if (userIdToDelete.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                DeleteUserResponse(
                    success = false,
                    message = "User ID is required"
                )
            )
            return@delete
        }

        // Verify that the authenticated user matches the user being deleted
        if (authenticatedUserId != userIdToDelete) {
            call.respond(
                HttpStatusCode.Forbidden,
                DeleteUserResponse(
                    success = false,
                    message = "You are not authorized to delete this user account"
                )
            )
            return@delete
        }

        try {
            // Parse the request body for additional confirmation
            val deleteRequest = if (requestBody.isNotBlank() && requestBody != "{}") {
                try {
                    Json.decodeFromString<DeleteUserRequest>(requestBody)
                } catch (_: Exception) {
                    // If parsing fails, create a default request
                    DeleteUserRequest(userIdToDelete, true)
                }
            } else {
                DeleteUserRequest(userIdToDelete, true)
            }

            // Verify userId in body matches the path parameter
            if (deleteRequest.userId != userIdToDelete) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    DeleteUserResponse(
                        success = false,
                        message = "User ID in request body does not match path parameter"
                    )
                )
                return@delete
            }

            // Perform the deletion
            val deleted = authDao.deleteUserAndAllData(userIdToDelete)

            if (deleted) {
                call.respond(
                    HttpStatusCode.OK,
                    DeleteUserResponse(
                        success = true,
                        message = "User account and all associated data have been permanently deleted"
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    DeleteUserResponse(
                        success = false,
                        message = "User not found or already deleted"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                DeleteUserResponse(
                    success = false,
                    message = "An error occurred while deleting the user: ${e.message}"
                )
            )
        }
    }
}

/**
 * Extension function to register all authentication routes
 */
fun Application.authenticationRoutes() {
    routing {
        deleteUserRoute()
    }
}
