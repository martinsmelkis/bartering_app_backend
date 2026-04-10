package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class ErasureTaskResponse(
    val id: Long,
    val userId: String,
    val taskType: String,
    val status: String,
    val storageScope: String,
    val targetRef: String?,
    val requestedBy: String?,
    val handledBy: String?,
    val dueAt: String?,
    val completedAt: String?,
    val notes: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CompleteErasureTaskRequest(
    val taskId: Long,
    val notes: String? = null
)

@Serializable
data class FailErasureTaskRequest(
    val taskId: Long,
    val notes: String? = null
)
