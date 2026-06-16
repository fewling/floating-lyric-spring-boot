package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.user.UserService
import com.floating.lyrics.auth.web.dto.MeResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth")
class AccountController(private val userService: UserService) {

	@GetMapping("/me")
	fun me(@AuthenticationPrincipal jwt: Jwt): MeResponse {
		val user = userService.findById(UUID.fromString(jwt.subject))
		return MeResponse(
			id = user.id!!.toString(),
			email = user.email,
			displayName = user.displayName,
			emailVerified = user.emailVerified,
		)
	}
}
