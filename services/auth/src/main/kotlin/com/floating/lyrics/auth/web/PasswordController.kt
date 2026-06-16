package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.password.PasswordService
import com.floating.lyrics.auth.web.dto.ChangePasswordRequest
import com.floating.lyrics.auth.web.dto.EmailOnlyRequest
import com.floating.lyrics.auth.web.dto.ResetPasswordRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth/password")
class PasswordController(
	private val passwordService: PasswordService,
) {

	@PostMapping("/forgot")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun forgot(@Valid @RequestBody req: EmailOnlyRequest) {
		passwordService.forgot(req.email)
	}

	@PostMapping("/reset")
	fun reset(@Valid @RequestBody req: ResetPasswordRequest) {
		passwordService.reset(req.token, req.newPassword)
	}

	@PostMapping("/change")
	fun change(
		@AuthenticationPrincipal jwt: Jwt,
		@Valid @RequestBody req: ChangePasswordRequest,
	) {
		passwordService.change(UUID.fromString(jwt.subject), req.oldPassword, req.newPassword)
	}
}
