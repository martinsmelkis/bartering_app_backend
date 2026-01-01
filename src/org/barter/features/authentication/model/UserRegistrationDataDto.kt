package org.barter.features.authentication.model

data class UserRegistrationDataDto(
    val name: String,
    val publicKey: String,
    val email: String,
    val id: String? = null,
    var password: String? = null,
)