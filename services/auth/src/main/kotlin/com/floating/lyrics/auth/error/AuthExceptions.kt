package com.floating.lyrics.auth.error

/** 400/401 — a presented token (refresh/verify/reset) is missing, unknown, expired, or used. */
class InvalidTokenException(message: String = "Invalid or expired token") : RuntimeException(message)

/** 401 — bad email/password at login. */
class InvalidCredentialsException(message: String = "Invalid email or password") : RuntimeException(message)

/** 403 — login attempted before the email was verified. */
class EmailNotVerifiedException(message: String = "Email not verified") : RuntimeException(message)

/** 409 — registration with an email that already exists. */
class DuplicateEmailException(message: String = "Email already registered") : RuntimeException(message)
