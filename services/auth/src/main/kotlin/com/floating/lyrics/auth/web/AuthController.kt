package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.password.PasswordService
import com.floating.lyrics.auth.token.AccessTokenService
import com.floating.lyrics.auth.token.RefreshTokenService
import com.floating.lyrics.auth.user.UserService
import com.floating.lyrics.auth.web.dto.EmailOnlyRequest
import com.floating.lyrics.auth.web.dto.LoginRequest
import com.floating.lyrics.auth.web.dto.RefreshRequest
import com.floating.lyrics.auth.web.dto.RegisterRequest
import com.floating.lyrics.auth.web.dto.TokenOnlyRequest
import com.floating.lyrics.contracts.TokenResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth")
class AuthController(
	private val userService: UserService,
	private val passwordService: PasswordService,
	private val accessTokens: AccessTokenService,
	private val refreshTokens: RefreshTokenService,
) {

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	fun register(@Valid @RequestBody req: RegisterRequest) {
		userService.register(req.email, req.password, req.displayName)
	}

	@PostMapping("/verify-email")
	fun verifyEmail(@Valid @RequestBody req: TokenOnlyRequest) {
		userService.verifyEmail(req.token)
	}

	@PostMapping("/resend-verification")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun resendVerification(@Valid @RequestBody req: EmailOnlyRequest) {
		userService.resendVerification(req.email)
	}

	@PostMapping("/login")
	fun login(@Valid @RequestBody req: LoginRequest): TokenResponse {
		val user = passwordService.authenticate(req.email, req.password)
		return issueTokens(user.id!!, user.email)
	}

	@PostMapping("/refresh")
	fun refresh(@Valid @RequestBody req: RefreshRequest): TokenResponse {
		val current = refreshTokens.validateActive(req.refreshToken)
		val newRefresh = refreshTokens.rotate(req.refreshToken)
		val access = accessTokens.mint(current.userId, userService.emailFor(current.userId))
		return TokenResponse(access.token, newRefresh, access.expiresInSeconds)
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun logout(@Valid @RequestBody req: RefreshRequest) {
		refreshTokens.revoke(req.refreshToken)
	}

	private fun issueTokens(userId: UUID, email: String): TokenResponse {
		val access = accessTokens.mint(userId, email)
		val refresh = refreshTokens.issue(userId)
		return TokenResponse(access.token, refresh, access.expiresInSeconds)
	}
}
