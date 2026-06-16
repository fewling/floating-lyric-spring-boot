package com.floating.lyrics.auth.token

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TokenHasherTest {
	private val hasher = TokenHasher()

	@Test
	fun `generated tokens are url-safe and unique`() {
		val a = hasher.newToken()
		val b = hasher.newToken()
		assertNotEquals(a, b)
		assert(a.matches(Regex("[A-Za-z0-9_-]+"))) { "token must be url-safe: $a" }
	}

	@Test
	fun `hash is deterministic`() {
		assertEquals(hasher.hash("abc"), hasher.hash("abc"))
		assertNotEquals(hasher.hash("abc"), hasher.hash("abd"))
	}
}
