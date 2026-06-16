package com.floating.lyrics.auth.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, UUID> {
	fun findByTokenHash(tokenHash: String): PasswordResetToken?
}
