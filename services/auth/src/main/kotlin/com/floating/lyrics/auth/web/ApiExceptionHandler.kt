package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.error.DuplicateEmailException
import com.floating.lyrics.auth.error.EmailNotVerifiedException
import com.floating.lyrics.auth.error.InvalidCredentialsException
import com.floating.lyrics.auth.error.InvalidTokenException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun onValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
		val msg = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
		return body(HttpStatus.BAD_REQUEST, "validation_failed", msg.ifBlank { "Invalid request" })
	}

	@ExceptionHandler(InvalidCredentialsException::class)
	fun onBadCreds(ex: InvalidCredentialsException) =
		body(HttpStatus.UNAUTHORIZED, "invalid_credentials", ex.message ?: "Invalid email or password")

	@ExceptionHandler(EmailNotVerifiedException::class)
	fun onUnverified(ex: EmailNotVerifiedException) =
		body(HttpStatus.FORBIDDEN, "email_not_verified", ex.message ?: "Email not verified")

	@ExceptionHandler(DuplicateEmailException::class)
	fun onDuplicate(ex: DuplicateEmailException) =
		body(HttpStatus.CONFLICT, "email_taken", ex.message ?: "Email already registered")

	@ExceptionHandler(InvalidTokenException::class)
	fun onBadToken(ex: InvalidTokenException) =
		body(HttpStatus.BAD_REQUEST, "invalid_token", ex.message ?: "Invalid or expired token")

	private fun body(status: HttpStatus, error: String, message: String) =
		ResponseEntity.status(status).body(ApiError(error, message))
}
