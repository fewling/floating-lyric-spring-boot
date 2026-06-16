package com.floating.lyrics.auth.user

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// @DataJpaTest and @AutoConfigureTestDatabase were removed in Spring Boot 4.1.
// We use @SpringBootTest which loads the full context using the test-classpath
// application.properties (in-memory H2) so Flyway applies the V1 schema.
@SpringBootTest
@Transactional
class UserRepositoryTest(@Autowired val users: UserRepository) {

	@Test
	fun `saves and finds a user by email (case-insensitive lookup expects lowercased storage)`() {
		val now = Instant.now()
		val saved = users.save(
			User(
				email = "jane@example.com",
				passwordHash = "hash",
				displayName = "Jane",
				emailVerified = false,
				createdAt = now,
				updatedAt = now,
			),
		)
		assertNotNull(saved.id)

		val found = users.findByEmail("jane@example.com")
		assertNotNull(found)
		assertEquals("Jane", found.displayName)
		assertTrue(users.existsByEmail("jane@example.com"))
		assertNull(users.findByEmail("nobody@example.com"))
	}
}
