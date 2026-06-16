package com.floating.lyrics.contracts

/**
 * Names of the claims the auth service puts in access-token JWTs, and the v1
 * scope placeholder. Both the issuer (auth) and any validating service (core)
 * reference these constants instead of hand-writing the strings.
 *
 * Standard claims (iss, sub, iat, exp, jti) keep their registered names and are
 * set via the JWT library, so only the custom claims live here.
 */
object JwtClaims {
	const val EMAIL = "email"
	const val SCOPES = "scopes"

	/** v1 placeholder scope; carries no enforcement until a real model exists. */
	const val SCOPE_APP_FULL = "app:full"
	val DEFAULT_SCOPES: List<String> = listOf(SCOPE_APP_FULL)
}
