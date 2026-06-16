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
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PasswordControllerTest {

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

	private fun registerVerified(email: String) {
		mvc.post("/auth/register") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to email, "password" to "password123"))
		}.andExpect { status { isCreated() } }
		val token = recorder.verifications.last().link.substringAfter("token=")
		mvc.post("/auth/verify-email") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token))
		}.andExpect { status { isOk() } }
	}

	@Test
	fun `forgot returns 202 even for unknown email`() {
		mvc.post("/auth/password/forgot") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "ghost@example.com"))
		}.andExpect { status { isAccepted() } }
	}

	@Test
	fun `reset with the emailed token returns 200 then fails on reuse`() {
		registerVerified("reset@example.com")
		mvc.post("/auth/password/forgot") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "reset@example.com"))
		}.andExpect { status { isAccepted() } }
		val token = recorder.resets.last().link.substringAfter("token=")

		mvc.post("/auth/password/reset") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token, "newPassword" to "brandnew123"))
		}.andExpect { status { isOk() } }

		mvc.post("/auth/password/reset") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token, "newPassword" to "another12345"))
		}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `change requires authentication`() {
		mvc.post("/auth/password/change") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("oldPassword" to "password123", "newPassword" to "newpass12345"))
		}.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `change with a valid bearer token and correct current password returns 200`() {
		registerVerified("chg@example.com")
		val id = users.findByEmail("chg@example.com")!!.id!!.toString()

		mvc.post("/auth/password/change") {
			with(jwt().jwt { it.subject(id).claim("email", "chg@example.com") })
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("oldPassword" to "password123", "newPassword" to "newpass12345"))
		}.andExpect { status { isOk() } }
	}
}
