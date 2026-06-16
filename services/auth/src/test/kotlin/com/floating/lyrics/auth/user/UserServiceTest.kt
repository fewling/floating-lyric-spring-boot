package com.floating.lyrics.auth.user

import com.floating.lyrics.auth.error.DuplicateEmailException
import com.floating.lyrics.auth.error.InvalidTokenException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Transactional
class UserServiceTest(
	@Autowired val service: UserService,
	@Autowired val users: UserRepository,
	@Autowired val recorder: RecordingEmailSender,
) {

	@BeforeEach
	fun clearRecorder() {
		recorder.verifications.clear()
		recorder.resets.clear()
	}

	@TestConfiguration
	class Config {
		@Bean
		@Primary
		fun recordingEmailSender() = RecordingEmailSender()
	}

	@Test
	fun `register stores a lowercased, unverified user and emails a verification link`() {
		service.register("Jane@Example.com", "password123", "Jane")

		val user = users.findByEmail("jane@example.com")
		assertNotNull(user)
		assertFalse(user.emailVerified)
		assertEquals(1, recorder.verifications.size)
		assertTrue(recorder.verifications.first().link.contains("token="))
	}

	@Test
	fun `duplicate registration is rejected`() {
		service.register("dup@example.com", "password123", null)
		assertThrows<DuplicateEmailException> {
			service.register("dup@example.com", "password123", null)
		}
	}

	@Test
	fun `verifying with the emailed token marks the user verified`() {
		service.register("v@example.com", "password123", null)
		val token = recorder.verifications.first().tokenParam()

		service.verifyEmail(token)

		assertTrue(users.findByEmail("v@example.com")!!.emailVerified)
	}

	@Test
	fun `verifying twice fails (token consumed)`() {
		service.register("v2@example.com", "password123", null)
		val token = recorder.verifications.first().tokenParam()
		service.verifyEmail(token)
		assertThrows<InvalidTokenException> { service.verifyEmail(token) }
	}
}
