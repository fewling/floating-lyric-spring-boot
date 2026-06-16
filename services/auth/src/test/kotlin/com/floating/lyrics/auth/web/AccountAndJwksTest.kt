package com.floating.lyrics.auth.web

import tools.jackson.databind.ObjectMapper
import com.floating.lyrics.auth.user.RecordingEmailSender
import com.floating.lyrics.auth.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AccountAndJwksTest {

	@TestConfiguration
	class Config {
		@Bean @Primary fun recordingEmailSender() = RecordingEmailSender()
	}

	@Autowired lateinit var context: WebApplicationContext
	@Autowired lateinit var json: ObjectMapper
	@Autowired lateinit var recorder: RecordingEmailSender
	@Autowired lateinit var users: UserRepository

	private lateinit var mvc: MockMvc

	@BeforeEach
	fun setup() {
		recorder.verifications.clear()
		recorder.resets.clear()
		mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
	}

	@Test
	fun `jwks endpoint is public and exposes one public key`() {
		mvc.get("/.well-known/jwks.json").andExpect {
			status { isOk() }
			jsonPath("$.keys.length()") { value(1) }
			jsonPath("$.keys[0].kty") { value("RSA") }
			jsonPath("$.keys[0].d") { doesNotExist() } // no private material
		}
	}

	@Test
	fun `me requires a token`() {
		mvc.get("/auth/me").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `me returns the caller's profile`() {
		// create a verified user, then mint a JWT for it via the resource-server test support
		mvc.post("/auth/register") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "me@example.com", "password" to "password123"))
		}.andExpect { status { isCreated() } }
		val token = recorder.verifications.last().link.substringAfter("token=")
		mvc.post("/auth/verify-email") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token))
		}.andExpect { status { isOk() } }
		val id = users.findByEmail("me@example.com")!!.id!!.toString()

		mvc.get("/auth/me") {
			with(jwt().jwt { it.subject(id).claim("email", "me@example.com") })
		}.andExpect {
			status { isOk() }
			jsonPath("$.email") { value("me@example.com") }
			jsonPath("$.emailVerified") { value(true) }
		}
	}
}
