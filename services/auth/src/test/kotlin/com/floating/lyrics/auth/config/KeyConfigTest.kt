package com.floating.lyrics.auth.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
class KeyConfigTest(
	@Autowired val encoder: JwtEncoder,
	@Autowired val decoder: JwtDecoder,
	@Autowired val jwkSet: com.nimbusds.jose.jwk.JWKSet,
) {

	@Test
	fun `a token signed by the encoder verifies with the decoder`() {
		val now = Instant.now()
		val claims = JwtClaimsSet.builder()
			.issuer("floating-lyric-auth")
			.subject("user-123")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(60))
			.build()
		val jwt = encoder.encode(JwtEncoderParameters.from(claims))

		val decoded = decoder.decode(jwt.tokenValue)
		assertEquals("user-123", decoded.subject)
	}

	@Test
	fun `jwk set exposes only public material`() {
		assertEquals(1, jwkSet.keys.size)
		assertTrue(jwkSet.keys.none { it.isPrivate })
	}
}
