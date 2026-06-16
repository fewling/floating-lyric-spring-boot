package com.floating.lyrics.auth.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.floating.lyrics.auth.user.RecordingEmailSender
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AuthControllerTest {

	@TestConfiguration
	class Config {
		@Bean @Primary fun recordingEmailSender() = RecordingEmailSender()
	}

	@Autowired lateinit var context: WebApplicationContext
	@Autowired lateinit var json: ObjectMapper
	@Autowired lateinit var recorder: RecordingEmailSender

	private lateinit var mvc: MockMvc

	@BeforeEach
	fun setup() {
		recorder.verifications.clear()
		recorder.resets.clear()
		mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
	}

	private fun register(email: String, pw: String = "password123") =
		mvc.post("/auth/register") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to email, "password" to pw, "displayName" to "T"))
		}

	private fun verifyLatest() {
		val token = recorder.verifications.last().link.substringAfter("token=")
		mvc.post("/auth/verify-email") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token))
		}.andExpect { status { isOk() } }
	}

	@Test
	fun `register returns 201 and emails a verification link`() {
		register("reg@example.com").andExpect { status { isCreated() } }
		assertNotNull(recorder.verifications.lastOrNull())
	}

	@Test
	fun `register with short password returns 400`() {
		register("short@example.com", "x").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `duplicate register returns 409`() {
		register("dupe@example.com").andExpect { status { isCreated() } }
		register("dupe@example.com").andExpect { status { isConflict() } }
	}

	@Test
	fun `login before verification returns 403`() {
		register("unv@example.com").andExpect { status { isCreated() } }
		mvc.post("/auth/login") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "unv@example.com", "password" to "password123"))
		}.andExpect { status { isForbidden() } }
	}

	@Test
	fun `verify then login returns tokens refresh rotates old refresh rejected`() {
		register("flow@example.com").andExpect { status { isCreated() } }
		verifyLatest()

		val loginBody = mvc.post("/auth/login") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "flow@example.com", "password" to "password123"))
		}.andExpect {
			status { isOk() }
			jsonPath("$.accessToken") { exists() }
			jsonPath("$.refreshToken") { exists() }
			jsonPath("$.tokenType") { value("Bearer") }
		}.andReturn().response.contentAsString

		val refresh1 = json.readTree(loginBody).get("refreshToken").asText()

		val refreshBody = mvc.post("/auth/refresh") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("refreshToken" to refresh1))
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val refresh2 = json.readTree(refreshBody).get("refreshToken").asText()

		// old refresh token no longer works
		mvc.post("/auth/refresh") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("refreshToken" to refresh1))
		}.andExpect { status { isBadRequest() } }

		// logout with the current token succeeds
		mvc.post("/auth/logout") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("refreshToken" to refresh2))
		}.andExpect { status { isNoContent() } }
	}
}
