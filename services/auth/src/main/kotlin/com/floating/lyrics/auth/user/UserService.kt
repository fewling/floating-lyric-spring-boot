package com.floating.lyrics.auth.user

import com.floating.lyrics.auth.config.AppProperties
import com.floating.lyrics.auth.email.EmailSender
import com.floating.lyrics.auth.error.DuplicateEmailException
import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.token.TokenHasher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class UserService(
	private val users: UserRepository,
	private val verificationTokens: EmailVerificationTokenRepository,
	private val passwordEncoder: PasswordEncoder,
	private val hasher: TokenHasher,
	private val emailSender: EmailSender,
	private val appProps: AppProperties,
) {
	private val verificationTtl = Duration.ofHours(24)

	@Transactional
	fun register(email: String, rawPassword: String, displayName: String?) {
		val normalized = email.trim().lowercase()
		if (users.existsByEmail(normalized)) throw DuplicateEmailException()

		val now = Instant.now()
		val user = users.save(
			User(
				email = normalized,
				passwordHash = passwordEncoder.encode(rawPassword)!!,
				displayName = displayName,
				emailVerified = false,
				createdAt = now,
				updatedAt = now,
			),
		)
		issueVerification(user)
	}

	@Transactional
	fun resendVerification(email: String) {
		val user = users.findByEmail(email.trim().lowercase()) ?: return // no enumeration
		if (user.emailVerified) return
		issueVerification(user)
	}

	@Transactional
	fun verifyEmail(rawToken: String) {
		val token = verificationTokens.findByTokenHash(hasher.hash(rawToken))
			?: throw InvalidTokenException()
		val now = Instant.now()
		if (token.consumedAt != null || token.expiresAt.isBefore(now)) throw InvalidTokenException()

		val user = users.findById(token.userId).orElseThrow { InvalidTokenException() }
		user.emailVerified = true
		user.updatedAt = now
		users.save(user)

		token.consumedAt = now
		verificationTokens.save(token)
	}

	@Transactional(readOnly = true)
	fun emailFor(userId: UUID): String =
		users.findById(userId).orElseThrow { IllegalStateException("User not found: $userId") }.email

	@Transactional(readOnly = true)
	fun findById(userId: UUID): User =
		users.findById(userId).orElseThrow { IllegalStateException("User not found: $userId") }

	private fun issueVerification(user: User) {
		val raw = hasher.newToken()
		verificationTokens.save(
			EmailVerificationToken(
				userId = user.id!!,
				tokenHash = hasher.hash(raw),
				expiresAt = Instant.now().plus(verificationTtl),
			),
		)
		val link = "${appProps.baseUrl}/auth/verify-email?token=$raw"
		emailSender.sendVerificationLink(user.email, link)
	}
}
