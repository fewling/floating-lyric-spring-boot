package com.floating.lyrics.auth.token

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
	@Column(name = "user_id", nullable = false)
	var userId: UUID,

	@Column(name = "token_hash", nullable = false, unique = true)
	var tokenHash: String,

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant,

	@Column(name = "revoked_at")
	var revokedAt: Instant? = null,

	@Column(name = "replaced_by")
	var replacedBy: UUID? = null,

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	var id: UUID? = null,
) {
	fun isActive(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)
}
