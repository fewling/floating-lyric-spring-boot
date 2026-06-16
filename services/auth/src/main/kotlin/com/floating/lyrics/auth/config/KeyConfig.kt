package com.floating.lyrics.auth.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

@Configuration
class KeyConfig {

	/**
	 * Parse the PEM private key once using standard Java security API (PKCS#8 format
	 * produced by `openssl genpkey`). Attach a stable kid (thumbprint) + RS256 metadata.
	 * Note: JWK.parseFromPEMEncodedObjects() requires BouncyCastle which is not available
	 * in Spring Boot 4.1.0's managed dependencies, so we use KeyFactory instead.
	 */
	@Bean
	fun signingKey(keyProps: KeyProperties, resourceLoader: ResourceLoader): RSAKey {
		val pem = resourceLoader.getResource(keyProps.location).inputStream
			.bufferedReader().use { it.readText() }
		// Strip PEM headers and decode base64 (PKCS#8 format from openssl genpkey)
		val b64 = pem
			.replace("-----BEGIN PRIVATE KEY-----", "")
			.replace("-----END PRIVATE KEY-----", "")
			.replace("\\s".toRegex(), "")
		val privateKeyBytes = Base64.getDecoder().decode(b64)
		val privateKey = KeyFactory.getInstance("RSA")
			.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as RSAPrivateCrtKey
		val rsaPublicKey = KeyFactory.getInstance("RSA")
			.generatePublic(java.security.spec.RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent))
			as java.security.interfaces.RSAPublicKey

		val rsaKey = RSAKey.Builder(rsaPublicKey)
			.privateKey(privateKey)
			.keyUse(KeyUse.SIGNATURE)
			.algorithm(JWSAlgorithm.RS256)
			.build()
		val kid = rsaKey.computeThumbprint().toString()
		return RSAKey.Builder(rsaPublicKey)
			.privateKey(privateKey)
			.keyID(kid)
			.keyUse(KeyUse.SIGNATURE)
			.algorithm(JWSAlgorithm.RS256)
			.build()
	}

	@Bean
	fun jwkSet(signingKey: RSAKey): JWKSet = JWKSet(signingKey.toPublicJWK())

	@Bean
	fun jwtEncoder(signingKey: RSAKey): JwtEncoder =
		NimbusJwtEncoder(ImmutableJWKSet(JWKSet(signingKey)))

	@Bean
	fun jwtDecoder(signingKey: RSAKey, tokenProps: TokenProperties): JwtDecoder {
		val decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build()
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(tokenProps.issuer))
		return decoder
	}
}
