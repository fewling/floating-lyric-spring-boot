package com.floating.lyrics.auth.web.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

private const val MIN_PASSWORD = 8

data class RegisterRequest(
	@field:Email @field:NotBlank val email: String,
	@field:NotBlank @field:Size(min = MIN_PASSWORD, message = "password must be at least 8 characters") val password: String,
	val displayName: String? = null,
)

data class LoginRequest(
	@field:Email @field:NotBlank val email: String,
	@field:NotBlank val password: String,
)

data class EmailOnlyRequest(
	@field:Email @field:NotBlank val email: String,
)

data class TokenOnlyRequest(
	@field:NotBlank val token: String,
)

data class RefreshRequest(
	@field:NotBlank val refreshToken: String,
)

data class ResetPasswordRequest(
	@field:NotBlank val token: String,
	@field:NotBlank @field:Size(min = MIN_PASSWORD, message = "password must be at least 8 characters") val newPassword: String,
)

data class ChangePasswordRequest(
	@field:NotBlank val oldPassword: String,
	@field:NotBlank @field:Size(min = MIN_PASSWORD, message = "password must be at least 8 characters") val newPassword: String,
)

data class MeResponse(
	val id: String,
	val email: String,
	val displayName: String?,
	val emailVerified: Boolean,
)
