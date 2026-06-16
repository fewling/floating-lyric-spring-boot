package com.floating.lyrics.auth.token

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Generates opaque random tokens and hashes them (SHA-256) for at-rest storage. */
@Component
class TokenHasher {
	private val random = SecureRandom()
	private val encoder = Base64.getUrlEncoder().withoutPadding()

	fun newToken(): String {
		val bytes = ByteArray(32)
		random.nextBytes(bytes)
		return encoder.encodeToString(bytes)
	}

	fun hash(token: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
		return encoder.encodeToString(digest)
	}
}
