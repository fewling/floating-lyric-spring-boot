package com.floating.lyrics.auth.token

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
	fun findByTokenHash(tokenHash: String): RefreshToken?

	// flush pending changes before the bulk update, and clear the persistence
	// context after so subsequent reads see the new revoked_at (a bulk JPQL UPDATE
	// bypasses the first-level cache and would otherwise return stale entities).
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
	fun revokeAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant): Int
}
