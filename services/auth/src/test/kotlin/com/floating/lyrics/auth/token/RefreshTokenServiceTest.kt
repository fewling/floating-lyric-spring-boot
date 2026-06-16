package com.floating.lyrics.auth.token

import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.user.User
import com.floating.lyrics.auth.user.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

// refresh_tokens has a FK to users, so tests must create a real user first.
@SpringBootTest
@Transactional
class RefreshTokenServiceTest(
	@Autowired val service: RefreshTokenService,
	@Autowired val repo: RefreshTokenRepository,
	@Autowired val users: UserRepository,
) {

	private fun newUser(): UUID {
		val now = Instant.now()
		return users.save(
			User(
				email = "test-${UUID.randomUUID()}@example.com",
				passwordHash = "hash",
				createdAt = now,
				updatedAt = now,
			),
		).id!!
	}

	@Test
	fun `issues a token that can be validated once`() {
		val userId = newUser()
		val raw = service.issue(userId)
		assertNotNull(raw)

		val resolved = service.validateActive(raw)
		assertEquals(userId, resolved.userId)
	}

	@Test
	fun `rotation revokes the old token and returns a new one`() {
		val userId = newUser()
		val first = service.issue(userId)

		val rotated = service.rotate(first)
		assertNotEquals(first, rotated.rawToken)
		assertEquals(userId, rotated.userId)

		// old token is no longer usable
		assertThrows<InvalidTokenException> { service.validateActive(first) }
		// new token works
		assertEquals(userId, service.validateActive(rotated.rawToken).userId)
	}

	@Test
	fun `unknown token is rejected`() {
		assertThrows<InvalidTokenException> { service.validateActive("not-a-real-token") }
	}

	@Test
	fun `revokeAllForUser invalidates outstanding tokens`() {
		val userId = newUser()
		val raw = service.issue(userId)
		service.revokeAllForUser(userId)
		assertThrows<InvalidTokenException> { service.validateActive(raw) }
	}

	@Test
	fun `revoke single token makes it invalid`() {
		val userId = newUser()
		val raw = service.issue(userId)
		service.revoke(raw)
		assertThrows<InvalidTokenException> { service.validateActive(raw) }
	}
}
