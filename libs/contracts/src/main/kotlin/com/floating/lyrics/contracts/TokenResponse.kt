package com.floating.lyrics.contracts

/**
 * Example shared contract: a DTO that more than one service needs to agree on.
 * Define request/response models that cross service boundaries here so callers
 * don't hand-roll their own copies.
 */
data class TokenResponse(
	val accessToken: String,
	val expiresInSeconds: Long,
)
