package com.floating.lyrics.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "auth.token")
data class TokenProperties(
	val issuer: String,
	val accessTtl: Duration,
	val refreshTtl: Duration,
)
