package com.floating.lyrics.auth.password

import com.floating.lyrics.auth.config.AppProperties
import com.floating.lyrics.auth.email.EmailSender
import com.floating.lyrics.auth.error.EmailNotVerifiedException
import com.floating.lyrics.auth.error.InvalidCredentialsException
import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.token.RefreshTokenService
import com.floating.lyrics.auth.token.TokenHasher
import com.floating.lyrics.auth.user.PasswordResetToken
import com.floating.lyrics.auth.user.PasswordResetTokenRepository
import com.floating.lyrics.auth.user.User
import com.floating.lyrics.auth.user.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class PasswordService(
	private val users: UserRepository,
	private val resetTokens: PasswordResetTokenRepository,
	private val refreshTokens: RefreshTokenService,
	private val passwordEncoder: PasswordEncoder,
	private val hasher: TokenHasher,
	private val emailSender: EmailSender,
	private val appProps: AppProperties,
) {
	private val resetTtl = Duration.ofHours(1)

	/** Verify credentials; returns the user or throws. Blocks unverified accounts. */
	@Transactional(readOnly = true)
	fun authenticate(email: String, rawPassword: String): User {
		val user = users.findByEmail(email.trim().lowercase())
			?: throw InvalidCredentialsException()
		if (!passwordEncoder.matches(rawPassword, user.passwordHash)) {
			throw InvalidCredentialsException()
		}
		if (!user.emailVerified) throw EmailNotVerifiedException()
		return user
	}

	@Transactional
	fun forgot(email: String) {
		val user = users.findByEmail(email.trim().lowercase()) ?: return // no enumeration
		val raw = hasher.newToken()
		resetTokens.save(
			PasswordResetToken(
				userId = user.id!!,
				tokenHash = hasher.hash(raw),
				expiresAt = Instant.now().plus(resetTtl),
			),
		)
		emailSender.sendPasswordResetLink(user.email, "${appProps.baseUrl}/auth/password/reset?token=$raw")
	}

	@Transactional
	fun reset(rawToken: String, newPassword: String) {
		val token = resetTokens.findByTokenHash(hasher.hash(rawToken)) ?: throw InvalidTokenException()
		val now = Instant.now()
		if (token.consumedAt != null || token.expiresAt.isBefore(now)) throw InvalidTokenException()

		val user = users.findById(token.userId).orElseThrow { InvalidTokenException() }
		user.passwordHash = passwordEncoder.encode(newPassword)!!
		user.updatedAt = now
		users.save(user)

		token.consumedAt = now
		resetTokens.save(token)

		refreshTokens.revokeAllForUser(user.id!!) // reset invalidates all sessions
	}

	@Transactional
	fun change(userId: UUID, currentPassword: String, newPassword: String) {
		val user = users.findById(userId).orElseThrow { InvalidCredentialsException() }
		if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
			throw InvalidCredentialsException()
		}
		user.passwordHash = passwordEncoder.encode(newPassword)!!
		user.updatedAt = Instant.now()
		users.save(user)
	}
}
