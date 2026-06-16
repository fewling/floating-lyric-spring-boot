package com.floating.lyrics.auth.token

import com.floating.lyrics.auth.config.TokenProperties
import com.floating.lyrics.contracts.JwtClaims
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Result of minting: the signed token plus its lifetime (for TokenResponse). */
data class MintedAccessToken(val token: String, val expiresInSeconds: Long)

@Service
class AccessTokenService(
	private val encoder: JwtEncoder,
	private val tokenProps: TokenProperties,
) {

	fun mint(userId: UUID, email: String): MintedAccessToken {
		val now = Instant.now()
		val expiresAt = now.plus(tokenProps.accessTtl)
		val claims = JwtClaimsSet.builder()
			.issuer(tokenProps.issuer)
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString()) // jti
			.claim(JwtClaims.EMAIL, email)
			.claim(JwtClaims.SCOPES, JwtClaims.DEFAULT_SCOPES)
			.build()
		val token = encoder.encode(JwtEncoderParameters.from(claims)).tokenValue
		return MintedAccessToken(token, tokenProps.accessTtl.seconds)
	}
}
