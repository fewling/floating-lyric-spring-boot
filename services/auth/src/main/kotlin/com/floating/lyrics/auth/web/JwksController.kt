package com.floating.lyrics.auth.web

import com.nimbusds.jose.jwk.JWKSet
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class JwksController(private val jwkSet: JWKSet) {

	/** Public keys for validators. jwkSet already contains only public material. */
	@GetMapping("/.well-known/jwks.json")
	fun jwks(): Map<String, Any> = jwkSet.toJSONObject()
}
