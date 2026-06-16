package com.floating.lyrics.auth.email

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Dev/v1 stub: writes the link to the application log instead of sending email.
 * MUST be replaced before any real signup.
 */
@Component
class LoggingEmailSender : EmailSender {
	private val log = LoggerFactory.getLogger(javaClass)

	override fun sendVerificationLink(to: String, link: String) {
		log.info("[email:verify] to={} link={}", to, link)
	}

	override fun sendPasswordResetLink(to: String, link: String) {
		log.info("[email:reset] to={} link={}", to, link)
	}
}
