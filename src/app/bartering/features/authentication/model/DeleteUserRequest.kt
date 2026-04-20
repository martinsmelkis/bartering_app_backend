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

@Serializable
data class RequestAccountDeletionByEmailRequest(
    val email: String
)

@Serializable
data class ConfirmAccountDeletionByEmailRequest(
    val token: String
)

@Serializable
data class AccountDeletionByEmailResponse(
    val success: Boolean,
    val message: String
)