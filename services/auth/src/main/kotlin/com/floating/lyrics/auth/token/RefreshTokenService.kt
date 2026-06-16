package com.floating.lyrics.auth.token

import com.floating.lyrics.auth.config.TokenProperties
import com.floating.lyrics.auth.error.InvalidTokenException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class RotatedRefreshToken(val rawToken: String, val userId: UUID)

@Service
class RefreshTokenService(
	private val repo: RefreshTokenRepository,
	private val hasher: TokenHasher,
	private val tokenProps: TokenProperties,
) {

	/** Issue a new opaque refresh token for a user; returns the RAW token (shown once). */
	@Transactional
	fun issue(userId: UUID): String {
		val raw = hasher.newToken()
		val now = Instant.now()
		repo.save(
			RefreshToken(
				userId = userId,
				tokenHash = hasher.hash(raw),
				expiresAt = now.plus(tokenProps.refreshTtl),
				createdAt = now,
			),
		)
		return raw
	}

	/** Resolve a raw token to its active record, or throw. */
	@Transactional(readOnly = true)
	fun validateActive(raw: String): RefreshToken {
		val token = repo.findByTokenHash(hasher.hash(raw)) ?: throw InvalidTokenException()
		if (!token.isActive(Instant.now())) throw InvalidTokenException()
		return token
	}

	/** Rotate: validate the old token, revoke it, issue + link a new one. Returns the new raw token and owning userId. */
	@Transactional
	fun rotate(raw: String): RotatedRefreshToken {
		val current = validateActive(raw)
		val now = Instant.now()
		val newRaw = hasher.newToken()
		val replacement = repo.save(
			RefreshToken(
				userId = current.userId,
				tokenHash = hasher.hash(newRaw),
				expiresAt = now.plus(tokenProps.refreshTtl),
				createdAt = now,
			),
		)
		current.revokedAt = now
		current.replacedBy = replacement.id
		repo.save(current)
		return RotatedRefreshToken(newRaw, current.userId)
	}

	@Transactional
	fun revoke(raw: String) {
		val token = repo.findByTokenHash(hasher.hash(raw)) ?: return
		if (token.revokedAt == null) {
			token.revokedAt = Instant.now()
			repo.save(token)
		}
	}

	@Transactional
	fun revokeAllForUser(userId: UUID) {
		repo.revokeAllForUser(userId, Instant.now())
	}
}
