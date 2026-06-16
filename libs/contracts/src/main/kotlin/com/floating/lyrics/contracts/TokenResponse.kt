package com.floating.lyrics.contracts

/**
 * Returned by the auth service's login and refresh endpoints. Shared so callers
 * (and other services that proxy auth) agree on the shape.
 */
data class TokenResponse(
	val accessToken: String,
	val refreshToken: String,
	val expiresInSeconds: Long,
	val tokenType: String = "Bearer",
)
