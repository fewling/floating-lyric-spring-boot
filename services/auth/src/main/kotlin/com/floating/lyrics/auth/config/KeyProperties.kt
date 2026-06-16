package com.floating.lyrics.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth.key")
data class KeyProperties(
	/** Spring resource location of the PEM-encoded RSA private key. */
	val location: String,
)
