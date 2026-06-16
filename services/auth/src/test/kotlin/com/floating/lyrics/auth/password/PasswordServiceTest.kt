package com.floating.lyrics.auth.password

import com.floating.lyrics.auth.error.EmailNotVerifiedException
import com.floating.lyrics.auth.error.InvalidCredentialsException
import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.token.RefreshTokenService
import com.floating.lyrics.auth.user.RecordingEmailSender
import com.floating.lyrics.auth.user.UserRepository
import com.floating.lyrics.auth.user.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Transactional
class PasswordServiceTest(
	@Autowired val passwords: PasswordService,
	@Autowired val userService: UserService,
	@Autowired val users: UserRepository,
	@Autowired val encoder: PasswordEncoder,
	@Autowired val refreshTokens: RefreshTokenService,
	@Autowired val recorder: RecordingEmailSender,
) {
	@TestConfiguration
	class Config {
		@Bean @Primary fun recordingEmailSender() = RecordingEmailSender()
	}

	@BeforeEach
	fun clearRecorder() {
		recorder.verifications.clear()
		recorder.resets.clear()
	}

	private fun registerVerified(email: String, pw: String) {
		userService.register(email, pw, null)
		val token = recorder.verifications.last().tokenParam()
		userService.verifyEmail(token)
	}

	@Test
	fun `login succeeds for a verified user with the right password`() {
		registerVerified("a@example.com", "password123")
		val user = passwords.authenticate("a@example.com", "password123")
		assertEquals("a@example.com", user.email)
	}

	@Test
	fun `login fails on wrong password`() {
		registerVerified("b@example.com", "password123")
		assertThrows<InvalidCredentialsException> { passwords.authenticate("b@example.com", "nope") }
	}

	@Test
	fun `login fails on unknown email with the same exception (no enumeration)`() {
		assertThrows<InvalidCredentialsException> { passwords.authenticate("ghost@example.com", "x") }
	}

	@Test
	fun `login blocked until email verified`() {
		userService.register("c@example.com", "password123", null)
		assertThrows<EmailNotVerifiedException> { passwords.authenticate("c@example.com", "password123") }
	}

	@Test
	fun `reset sets a new password, consumes the token, and revokes all sessions`() {
		registerVerified("d@example.com", "password123")
		val userId = users.findByEmail("d@example.com")!!.id!!
		val outstandingRefresh = refreshTokens.issue(userId) // an active session before reset

		passwords.forgot("d@example.com")
		val token = recorder.resets.last().tokenParam()

		passwords.reset(token, "newpassword1")

		val user = users.findByEmail("d@example.com")!!
		assertTrue(encoder.matches("newpassword1", user.passwordHash))
		// token is single-use
		assertThrows<InvalidTokenException> { passwords.reset(token, "another1234") }
		// reset revoked every outstanding refresh token (spec requirement)
		assertThrows<InvalidTokenException> { refreshTokens.validateActive(outstandingRefresh) }
	}

	@Test
	fun `change password requires the correct current password`() {
		registerVerified("e@example.com", "password123")
		val id = users.findByEmail("e@example.com")!!.id!!

		assertThrows<InvalidCredentialsException> { passwords.change(id, "wrong", "newpass1234") }
		passwords.change(id, "password123", "newpass1234")
		assertTrue(encoder.matches("newpass1234", users.findById(id).get().passwordHash))
	}

	@Test
	fun `forgot for unknown email is silent`() {
		passwords.forgot("nobody@example.com") // must not throw
		assertEquals(0, recorder.resets.size)
	}
}
