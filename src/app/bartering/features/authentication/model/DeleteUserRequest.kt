package app.bartering.features.authentication.model

import kotlinx.serialization.Serializable

/**
 * Data class for user deletion request body
 */
@Serializable
data class DeleteUserRequest(
    val userId: String,
    val confirmation: Boolean = false
)