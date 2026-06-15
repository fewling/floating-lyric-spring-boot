# Auth Service — v1 Design

- **Status:** Approved (scope) — pending spec review
- **Date:** 2026-06-15
- **Module:** `services/auth`
- **Author:** felix.wong

## Background

Floating Lyric is an Android app that displays floating, AI-translated lyrics over
other apps. Today the app has **no user login**: it relies on Firebase App Check
(app attestation) and stores LRC files locally on-device (Hive). The backend logic
(lyric search, AI translation) currently lives in Firebase Cloud Functions.

This Spring Boot monorepo is a new/parallel backend. `services/auth` is currently a
bare `@SpringBootApplication` stub. **User authentication is a brand-new capability**
introduced by this service — there is no existing user model to migrate.

The monorepo is a microservices architecture: services talk over the network and
share only `libs/contracts` at compile time. Whatever `auth` issues, other services
(`core`, future services) must be able to validate independently.

## Goals

- Let end-users register and log in with email + password.
- Issue tokens that other services can validate **without calling back to `auth`**.
- Keep sessions alive securely (short access token + refresh).
- Ship a runnable, fully testable service with **zero external infrastructure** (no
  Docker, no SMTP account) for v1.

## Non-Goals (explicitly deferred, documented here so they are not silently dropped)

- **Social / OAuth login** (Google/Apple/etc.) — planned next increment. The token
  and user model are designed so this can be added without breaking validators.
- **Brute-force protection** (login rate-limiting, account lockout) — first hardening
  to add once real users exist. v1 login is unthrottled (documented risk).
- **MFA / TOTP.**
- **Scope enforcement** — see Authorization below.
- **Real email delivery (SMTP)** — see Email below.
- **PostgreSQL** — v1 uses file-mode H2; Postgres is a later migration.
- **`core` resource-server wiring** — a follow-up task; this spec only delivers the
  contract (`JWKS` + claim names) that enables it.

## Scope Decisions

Each decision below was made deliberately during scoping; rationale is recorded so
future readers understand the trade-off, not just the choice.

| Area | Decision | Rationale |
|---|---|---|
| Principals | Human end-users only | No machine-to-machine need today. |
| Login method | Email + password | Social login deferred to a clean follow-up to avoid the account-linking problem in v1. |
| Token model | Stateless JWT, validated locally by other services | Avoids `auth` on the hot path of every request; fits the existing `TokenResponse` contract. |
| Sessions | Short access JWT + **rotating** refresh token | Stateless tokens can't be instantly revoked; rotating refresh tokens give logout/revoke and good mobile UX. Accepts a server-side refresh store. |
| Signing | Asymmetric RS256 + **JWKS endpoint** | Validators hold only the public key (can't forge tokens); JWKS allows key rotation without redeploying validators. |
| Authorization | `scopes` claim as a **forward-compatible placeholder** | A real scope need is hypothetical with no consumer today; build the claim, not the machinery. |
| Persistence | File-mode H2 + Flyway migrations | Zero-infra start that still survives restarts (so refresh tokens persist). Postgres later. |
| Email flows | Verification + password reset, via a stubbed sender | Both flows requested; real SMTP not required to make them functional in dev. |

## Architecture

**Stack:** Spring Security (BCrypt password hashing, security filter chain) + Nimbus
JOSE (JWT signing & JWKS, available via `spring-security-oauth2-jose`). Spring
Authorization Server is intentionally **not** used — it is a full OAuth2/OIDC
authorization server, far more than custom-credential + JWT minting requires.

Package layout under `services/auth/src/main/kotlin/com/floating/lyrics/auth/`:

| Package | Responsibility | Key types | Depends on |
|---|---|---|---|
| `web` | HTTP controllers + request/response DTOs | `AuthController`, `PasswordController`, `AccountController`, `JwksController` | `user`, `token` |
| `user` | User identity, registration, email verification | `User`, `UserRepository`, `UserService`, `EmailVerificationService` | `email`, persistence |
| `token` | Token minting, refresh rotation, signing keys | `AccessTokenService`, `RefreshTokenService`, `KeyService` | persistence (refresh store), `config` |
| `email` | Outbound email abstraction | `EmailSender` (interface), `LoggingEmailSender` | — |
| `security` | Spring Security config, password encoder, JWT auth filter for authenticated routes | `SecurityConfig`, `JwtAuthenticationFilter` | `token` |
| `config` | Typed configuration (token lifetimes, issuer, keys) | `TokenProperties`, `KeyProperties` | — |

Each unit has a single clear responsibility and a narrow public surface:

- `AccessTokenService` — *mint a signed access JWT for a user; validate one.* Input: a
  user (id, email). Output: a signed JWT string. Internals (Nimbus, key handling)
  are not visible to callers. It is the **single owner of token verification**:
  `security`'s `JwtAuthenticationFilter` delegates to it for the Bearer-protected
  routes rather than duplicating the verification logic.
- `RefreshTokenService` — *issue, rotate, and revoke refresh tokens.* Input: user id /
  raw refresh token. Output: a new opaque token + persisted hashed record.
- `KeyService` — *own the RSA key pair and expose it as a JWKS.* Input: none. Output:
  the signing key (for `AccessTokenService`) and a public JWK set (for the JWKS
  endpoint).
- `EmailSender` — *send a templated email.* One method; `LoggingEmailSender` logs the
  link. Swapping in SMTP touches only this unit.

## Token & Key Design

**Access JWT** (TTL ~15 minutes):

| Claim | Value |
|---|---|
| `iss` | configured issuer, e.g. `floating-lyric-auth` |
| `sub` | user UUID |
| `email` | user email |
| `scopes` | placeholder default `["app:full"]` |
| `iat`, `exp` | issued-at / expiry |
| `jti` | unique token id |
| header `kid` | key id, for JWKS key selection |

**Refresh token** (TTL ~30 days):

- Opaque 256-bit random value returned to the client; stored **hashed** (never in
  plaintext) in `refresh_tokens`.
- On `POST /auth/refresh`: validate the presented token → mark the old record revoked
  → issue a new token (rotation) → return a fresh access + refresh pair.
- A reused (already-rotated) refresh token is rejected. **Token-reuse-family
  revocation** (revoke all of a user's refresh tokens on detected reuse) is a noted
  deferred hardening, not in v1.

**Keys / JWKS:**

- An RSA key pair is loaded from configuration. For dev, a key pair is generated and
  committed to local config (never a production key).
- v1 serves **exactly one** key, so the JWKS is an array of one and `kid` selection is
  trivial. Multi-key rotation (publishing old + new during a rollover) is a future
  increment — the endpoint shape already supports it.
- The public key is served at `GET /.well-known/jwks.json`.
- Validators (`core`, future services) configure themselves as OAuth2 resource
  servers pointing at this JWKS URL — no shared secret. (Wiring `core` is out of
  scope for this spec.)

## Data Model (Flyway migrations)

```
users
  id                uuid        primary key
  email             text        unique, stored lowercased
  password_hash     text        not null            -- not null in v1; becomes nullable when social login lands
  display_name      text        nullable
  email_verified    boolean     not null default false
  created_at        timestamptz not null
  updated_at        timestamptz not null

refresh_tokens
  id                uuid        primary key
  user_id           uuid        not null  -> users.id
  token_hash        text        not null  unique
  expires_at        timestamptz not null
  revoked_at        timestamptz nullable
  replaced_by       uuid        nullable  -> refresh_tokens.id   -- rotation chain
  created_at        timestamptz not null

email_verification_tokens
  id                uuid        primary key
  user_id           uuid        not null  -> users.id
  token_hash        text        not null  unique
  expires_at        timestamptz not null
  consumed_at       timestamptz nullable

password_reset_tokens
  id                uuid        primary key
  user_id           uuid        not null  -> users.id
  token_hash        text        not null  unique
  expires_at        timestamptz not null
  consumed_at       timestamptz nullable
```

Notes:
- Scopes are **not** stored. They are injected at mint time as the placeholder
  default. A `scopes` column (or a roles/permissions model) is added only when a real
  distinction exists.
- All single-use tokens (verification, reset) are stored hashed and have an expiry +
  `consumed_at` so they cannot be replayed.

## API Surface

| Method & path | Auth | Body | Success | Notes |
|---|---|---|---|---|
| `POST /auth/register` | none | `{email, password, displayName?}` | `201` | Creates an unverified user; sends a verification email. |
| `POST /auth/verify-email` | none | `{token}` | `200` | Marks the user verified; consumes the token. |
| `POST /auth/resend-verification` | none | `{email}` | `202` | Re-issues a verification email. Always `202` (no account enumeration). |
| `POST /auth/login` | none | `{email, password}` | `200` `TokenResponse` | **`403` if email not verified.** |
| `POST /auth/refresh` | none | `{refreshToken}` | `200` `TokenResponse` | Rotates the refresh token. |
| `POST /auth/logout` | none | `{refreshToken}` | `204` | Revokes the presented refresh token. |
| `POST /auth/password/forgot` | none | `{email}` | `202` | Sends a reset link. Always `202` (no enumeration). |
| `POST /auth/password/reset` | none | `{token, newPassword}` | `200` | Sets a new password; **revokes all of the user's refresh tokens.** |
| `POST /auth/password/change` | Bearer | `{oldPassword, newPassword}` | `200` | Authenticated self-service change. |
| `GET /auth/me` | Bearer | — | `200` profile | Returns id, email, displayName, emailVerified. |
| `GET /.well-known/jwks.json` | none | — | `200` JWKS | Public keys for validators. |

**Error model:** validation and auth failures return a consistent JSON error body
(`{error, message}`); 4xx for client errors (400 validation, 401 bad credentials, 403
unverified), 409 for duplicate registration email. Endpoints that take an email and
could leak account existence (`resend-verification`, `password/forgot`) always return
a 2xx regardless of whether the account exists.

**Decision — unverified login:** unverified users **cannot log in** (`login` returns
`403` with a code the client can map to a "verify your email" prompt; they can call
`resend-verification`). The alternative — logging them in with restricted access — was
rejected because no access restrictions are defined yet. A consequence: the
Bearer-protected routes (`/auth/me`, `/auth/password/change`) are only ever reachable
by verified users, since an unverified user can never obtain an access token.

**Password policy:** `password` / `newPassword` are validated at the `web` layer with
a minimum length of 8 characters (a deliberately minimal rule for v1; the `400`
validation path on `register`, `password/reset`, and `password/change` enforces it).

## Contracts (`libs/contracts`)

- **Extend `TokenResponse`** to: `accessToken`, `refreshToken`, `tokenType` (default
  `"Bearer"`), `expiresInSeconds` (access-token TTL). Returned by `login` and
  `refresh`.
- **Add a `JwtClaims` constants holder** — the claim names (`sub`, `email`, `scopes`,
  …) and scope constants — so the issuer (`auth`) and validators (`core`) agree at
  compile time rather than duplicating string literals.

Request DTOs for the mobile client are not added to `libs/contracts` — the Flutter
app generates its client from a separate OpenAPI source of truth, and request DTOs
are not cross-service contracts. They live in `auth`'s `web` package.

## Email

`EmailSender` is a one-method interface. v1 ships `LoggingEmailSender`, which writes
the verification / reset **link** to the application log. Both flows are fully
functional in dev — the link is retrieved from the log. Real delivery (Spring Mail +
a provider) is a later, isolated swap of this one implementation.

This is flagged as the **first thing to replace before any real signup.**

## Testing

- **Unit:** access-token minting/validation, refresh issue/rotate/revoke, password
  hashing/verification, JWKS shape.
- **Integration (Spring Boot Test + MockMvc, H2):** every endpoint, including the
  unverified-login `403`, refresh rotation (old token rejected after use),
  password-reset revoking all sessions, and duplicate-registration `409`.
- `LoggingEmailSender` is captured in tests to assert verification/reset links are
  issued.

## Risks

- **No brute-force protection** — login is unthrottled in v1. Acceptable for an
  initial cut; first hardening once real users exist.
- **`LoggingEmailSender`** — emails are only logged; must be replaced before real
  signups.
- **H2 vs Postgres dialect drift** — file-mode H2 mitigates data loss, but is not
  Postgres. Keep Flyway migrations dialect-portable and watch for drift before the
  Postgres migration.

## Future Increments (not part of this spec)

1. Social / OAuth login with verified-email account linking.
2. `core` (and other services) wired as OAuth2 resource servers against the JWKS.
3. Brute-force protection.
4. Real SMTP email delivery.
5. PostgreSQL migration.
6. Real scope/permission model once a concrete access distinction exists.
