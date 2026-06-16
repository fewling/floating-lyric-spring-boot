package com.floating.lyrics.auth.user

import com.floating.lyrics.auth.email.EmailSender

/** Test double capturing emailed links so tests can extract the `token=` value. */
class RecordingEmailSender : EmailSender {
	data class Sent(val to: String, val link: String) {
		fun tokenParam(): String = link.substringAfter("token=")
	}

	val verifications = mutableListOf<Sent>()
	val resets = mutableListOf<Sent>()

	override fun sendVerificationLink(to: String, link: String) {
		verifications += Sent(to, link)
	}

	override fun sendPasswordResetLink(to: String, link: String) {
		resets += Sent(to, link)
	}
}
