package com.floating.lyrics.auth.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EmailVerificationTokenRepository : JpaRepository<EmailVerificationToken, UUID> {
	fun findByTokenHash(tokenHash: String): EmailVerificationToken?
}
