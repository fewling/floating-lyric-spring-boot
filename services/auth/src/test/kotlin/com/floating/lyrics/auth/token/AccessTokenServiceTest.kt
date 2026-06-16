package com.floating.lyrics.auth.token

import com.floating.lyrics.contracts.JwtClaims
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
class AccessTokenServiceTest(
	@Autowired val service: AccessTokenService,
	@Autowired val decoder: JwtDecoder,
) {

	@Test
	fun `minted token carries sub, email, scopes and expiry`() {
		val userId = UUID.randomUUID()
		val minted = service.mint(userId, "jane@example.com")

		assertEquals(900, minted.expiresInSeconds) // PT15M

		val jwt = decoder.decode(minted.token)
		assertEquals(userId.toString(), jwt.subject)
		assertEquals("jane@example.com", jwt.getClaimAsString(JwtClaims.EMAIL))
		assertEquals(JwtClaims.DEFAULT_SCOPES, jwt.getClaimAsStringList(JwtClaims.SCOPES))
		// issuer is stored as a plain string claim; getClaimAsString avoids URL conversion
		assertEquals("floating-lyric-auth", jwt.getClaimAsString("iss"))
	}
}
