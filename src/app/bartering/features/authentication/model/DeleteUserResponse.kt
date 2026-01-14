package app.bartering.features.authentication.model

import kotlinx.serialization.Serializable

/**
 * Response for user deletion
 */
@Serializable
data class DeleteUserResponse(
    val success: Boolean,
    val message: String
)