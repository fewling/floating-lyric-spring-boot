package com.floating.lyrics.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth")
data class AppProperties(
	/** Public base URL used to build verification/reset links in emails. */
	val baseUrl: String,
)
