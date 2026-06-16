package com.floating.lyrics.contracts

import kotlin.test.Test
import kotlin.test.assertEquals

class JwtClaimsTest {

	@Test
	fun `claim names are the expected strings`() {
		assertEquals("email", JwtClaims.EMAIL)
		assertEquals("scopes", JwtClaims.SCOPES)
	}

	@Test
	fun `default scopes is the app full placeholder`() {
		assertEquals(listOf("app:full"), JwtClaims.DEFAULT_SCOPES)
		assertEquals("app:full", JwtClaims.SCOPE_APP_FULL)
	}

	@Test
	fun `token response carries access and refresh tokens with bearer default`() {
		val r = TokenResponse(accessToken = "a", refreshToken = "r", expiresInSeconds = 900)
		assertEquals("Bearer", r.tokenType)
		assertEquals("a", r.accessToken)
		assertEquals("r", r.refreshToken)
		assertEquals(900, r.expiresInSeconds)
	}
}
