package com.floating.lyrics.auth.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "password_reset_tokens")
class PasswordResetToken(
	@Column(name = "user_id", nullable = false)
	var userId: UUID,

	@Column(name = "token_hash", nullable = false, unique = true)
	var tokenHash: String,

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant,

	@Column(name = "consumed_at")
	var consumedAt: Instant? = null,

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	var id: UUID? = null,
)
