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
@Table(name = "users")
class User(
	@Column(nullable = false, unique = true)
	var email: String,

	@Column(name = "password_hash", nullable = false)
	var passwordHash: String,

	@Column(name = "display_name")
	var displayName: String? = null,

	@Column(name = "email_verified", nullable = false)
	var emailVerified: Boolean = false,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant,

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	var id: UUID? = null,
)
