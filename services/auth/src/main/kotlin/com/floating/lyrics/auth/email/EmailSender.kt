package com.floating.lyrics.auth.email

/**
 * Outbound email. v1 ships only LoggingEmailSender; swapping in real SMTP touches
 * only this package.
 */
interface EmailSender {
	fun sendVerificationLink(to: String, link: String)
	fun sendPasswordResetLink(to: String, link: String)
}
