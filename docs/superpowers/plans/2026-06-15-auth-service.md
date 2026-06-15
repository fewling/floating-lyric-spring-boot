# Auth Service v1 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `services/auth` microservice: email+password registration/login that issues RS256-signed stateless JWT access tokens (validated by other services via a JWKS endpoint) plus rotating refresh tokens, with email verification, password reset, and change-password flows.

**Architecture:** Spring Boot service over file-mode H2 (Flyway-managed schema, Spring Data JPA). Spring Security provides BCrypt hashing and — via its OAuth2 **resource-server** support — Bearer validation of our own access tokens on protected routes (the shared `JwtDecoder` bean is the single source of token-verification truth). Nimbus JOSE (bundled with the resource-server starter) signs JWTs and exposes the public key as a JWK Set. Email is a stubbed `LoggingEmailSender`. Tokens that cross service boundaries are agreed in `libs/contracts`.

**Tech Stack:** Kotlin 2.3.21 / Spring Boot 4.1.0 / Java 25 / Gradle 9.5.1; Spring Web, Spring Security (+ oauth2-resource-server / oauth2-jose / Nimbus), Spring Data JPA, Flyway, H2; JUnit 5 + Spring Boot Test + MockMvc.

**Spec:** `docs/superpowers/specs/2026-06-15-auth-service-design.md`

**Out of scope (per spec):** social login, brute-force protection, MFA, real SMTP, PostgreSQL, scope enforcement, wiring `core` as a resource server.

---

## Conventions for the implementer

- All service code lives under `services/auth/src/main/kotlin/com/floating/lyrics/auth/` (shortened below to **`auth/`**). Tests mirror under `services/auth/src/test/kotlin/com/floating/lyrics/auth/` (**`authTest/`**).
- Run the auth module's tests with:
  `./gradlew :services:auth:test --tests "<fully.qualified.TestClass>"`
- Run a single test method by appending the method: `--tests "...TestClass.methodName"`.
- Full module build (compile + all tests): `./gradlew :services:auth:build`
- Commit after every green step. Use conventional-commit prefixes (`feat:`, `test:`, `chore:`, `build:`).
- This plan refines two spec wordings for the better; both are noted inline:
  1. Token verification is owned by a single shared `JwtDecoder` bean consumed by both `AccessTokenService` and Spring Security's resource-server config (instead of a hand-written filter).
  2. Persistence uses Spring Data **JPA** (the spec said "Spring Data" generically).

---

## File Structure

**Build / config (shared + module):**
- Modify `build-logic/build.gradle.kts` — add `kotlin-noarg` to the plugin classpath.
- Modify `build-logic/src/main/kotlin/floating-lyric.spring-service.gradle.kts` — apply `kotlin("plugin.jpa")`.
- Modify `services/auth/build.gradle.kts` — add runtime/test dependencies.
- Modify `services/auth/src/main/resources/application.properties` — datasource, JPA, Flyway, token/key config, port.
- Create `services/auth/src/main/resources/keys/dev-signing-key.pem` — dev-only RSA private key.
- Create `services/auth/src/main/resources/db/migration/V1__init_auth.sql` — schema for all four tables.

**Contracts (cross-service, no Spring):**
- Modify `libs/contracts/src/main/kotlin/com/floating/lyrics/contracts/TokenResponse.kt`.
- Create `libs/contracts/src/main/kotlin/com/floating/lyrics/contracts/JwtClaims.kt`.

**Service code under `auth/`:**
- `config/TokenProperties.kt`, `config/KeyProperties.kt`, `config/AppProperties.kt`, `config/KeyConfig.kt`
- `user/User.kt`, `user/UserRepository.kt`, `user/UserService.kt`
- `user/EmailVerificationToken.kt`, `user/EmailVerificationTokenRepository.kt`
- `user/PasswordResetToken.kt`, `user/PasswordResetTokenRepository.kt`
- `token/RefreshToken.kt`, `token/RefreshTokenRepository.kt`, `token/RefreshTokenService.kt`, `token/AccessTokenService.kt`, `token/TokenHasher.kt`
- `email/EmailSender.kt`, `email/LoggingEmailSender.kt`
- `password/PasswordService.kt`
- `security/SecurityConfig.kt`, `security/PasswordConfig.kt`
- `web/AuthController.kt`, `web/PasswordController.kt`, `web/AccountController.kt`, `web/JwksController.kt`
- `web/dto/*.kt` (request/response DTOs)
- `web/ApiError.kt`, `web/ApiExceptionHandler.kt`
- `error/AuthExceptions.kt` (domain exceptions)

Each unit has one responsibility; services never reach into controllers, and the `token`/`user`/`email` packages are independently testable.

---

## Chunk 1: Build wiring, contracts, configuration, and schema

This chunk makes the module compile with all dependencies, defines the cross-service contracts, wires configuration properties, generates the dev signing key, and creates the database schema. At the end, `./gradlew :services:auth:build` passes and the Spring context loads with Flyway applying the schema to H2.

### Task 1: Enable the Kotlin JPA plugin across services

JPA entities in Kotlin need no-arg constructors and non-final classes. `kotlin("plugin.jpa")` (from `kotlin-noarg`) provides this, mirroring how `kotlin("plugin.spring")` (from `kotlin-allopen`, already present) is wired. Applying it in the shared convention plugin is harmless for services without entities.

**Files:**
- Modify: `build-logic/build.gradle.kts`
- Modify: `build-logic/src/main/kotlin/floating-lyric.spring-service.gradle.kts`

- [ ] **Step 1: Add `kotlin-noarg` to the build-logic classpath**

In `build-logic/build.gradle.kts`, add one line to the `dependencies` block (next to `kotlin-allopen`):

```kotlin
dependencies {
	implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
	implementation("org.jetbrains.kotlin:kotlin-allopen:2.3.21") // kotlin("plugin.spring")
	implementation("org.jetbrains.kotlin:kotlin-noarg:2.3.21") // kotlin("plugin.jpa")
	implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.0")
	implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
```

- [ ] **Step 2: Apply `kotlin("plugin.jpa")` in the spring-service convention plugin**

In `build-logic/src/main/kotlin/floating-lyric.spring-service.gradle.kts`, add to the `plugins` block:

```kotlin
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	kotlin("plugin.jpa")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}
```

- [ ] **Step 3: Verify the build still configures**

Run: `./gradlew :services:auth:help -q`
Expected: configures with no errors (downloads `kotlin-noarg` if needed, BUILD SUCCESSFUL).

- [ ] **Step 4: Commit**

```bash
git add build-logic/build.gradle.kts build-logic/src/main/kotlin/floating-lyric.spring-service.gradle.kts
git commit -m "build: enable kotlin jpa plugin for services"
```

### Task 2: Add auth service dependencies

**Files:**
- Modify: `services/auth/build.gradle.kts`

- [ ] **Step 1: Declare dependencies**

Replace the `dependencies` block in `services/auth/build.gradle.kts` with:

```kotlin
dependencies {
	// Shared cross-service contracts (DTOs + JWT claim names).
	implementation(project(":libs:contracts"))

	// Web + JSON (Kotlin module for data-class (de)serialization).
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	// Bean Validation for request DTOs (@Email, @Size, ...).
	implementation("org.springframework.boot:spring-boot-starter-validation")

	// Security: BCrypt + (resource-server) Bearer validation of our own JWTs.
	// The resource-server starter also pulls in spring-security-oauth2-jose,
	// which provides NimbusJwtEncoder/Decoder and the Nimbus JWK classes used
	// for signing and the JWKS endpoint.
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// Persistence: Spring Data JPA + Flyway + file-mode H2.
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.flywaydb:flyway-core")
	runtimeOnly("org.flywaydb:flyway-database-h2")
	runtimeOnly("com.h2database:h2")

	// Security test helpers (spring-security-test) for MockMvc with JWTs.
	testImplementation("org.springframework.security:spring-security-test")
}
```

- [ ] **Step 2: Verify dependencies resolve and the existing context test still passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.AuthApplicationTests"`
Expected: At this point it may FAIL because there is no datasource/Flyway config yet (the JPA starter is now present). That is expected and fixed in Task 4–5. The expected failure is a datasource auto-config error — typically `Failed to configure a DataSource: 'url' attribute is not specified` (or a Flyway/Hibernate startup error). It must **not** be a Gradle dependency-resolution error (e.g. "Could not resolve ...") — that would mean a typo in Task 1's Step 1.

> Note to implementer: do not try to fix the context test here. Tasks 4 and 5 add the datasource + schema that make it pass. Proceed.

- [ ] **Step 3: Commit**

```bash
git add services/auth/build.gradle.kts
git commit -m "build: add web, security, jpa, flyway, h2 deps to auth"
```

### Task 3: Cross-service contracts (TokenResponse + JwtClaims)

`TokenResponse` is the body returned by `login`/`refresh`. `JwtClaims` centralizes claim names + scope constants so the issuer (`auth`) and future validators (`core`) never duplicate string literals.

**Files:**
- Modify: `libs/contracts/src/main/kotlin/com/floating/lyrics/contracts/TokenResponse.kt`
- Create: `libs/contracts/src/main/kotlin/com/floating/lyrics/contracts/JwtClaims.kt`
- Test: `libs/contracts/src/test/kotlin/com/floating/lyrics/contracts/JwtClaimsTest.kt`

- [ ] **Step 1: Write the failing test for the contracts**

Create `libs/contracts/src/test/kotlin/com/floating/lyrics/contracts/JwtClaimsTest.kt`:

```kotlin
package com.floating.lyrics.contracts

import kotlin.test.Test
import kotlin.test.assertEquals

class JwtClaimsTest {

	@Test
	fun `claim names are the expected strings`() {
		assertEquals("email", JwtClaims.EMAIL)
		assertEquals("scopes", JwtClaims.SCOPES)
	}

	@Test
	fun `default scopes is the app full placeholder`() {
		assertEquals(listOf("app:full"), JwtClaims.DEFAULT_SCOPES)
		assertEquals("app:full", JwtClaims.SCOPE_APP_FULL)
	}

	@Test
	fun `token response carries access and refresh tokens with bearer default`() {
		val r = TokenResponse(accessToken = "a", refreshToken = "r", expiresInSeconds = 900)
		assertEquals("Bearer", r.tokenType)
		assertEquals("a", r.accessToken)
		assertEquals("r", r.refreshToken)
		assertEquals(900, r.expiresInSeconds)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :libs:contracts:test --tests "com.floating.lyrics.contracts.JwtClaimsTest"`
Expected: FAIL — `JwtClaims` unresolved and `TokenResponse` has no `refreshToken`/`tokenType`.

- [ ] **Step 3: Extend `TokenResponse`**

Replace `libs/contracts/src/main/kotlin/com/floating/lyrics/contracts/TokenResponse.kt`:

```kotlin
package com.floating.lyrics.contracts

/**
 * Returned by the auth service's login and refresh endpoints. Shared so callers
 * (and other services that proxy auth) agree on the shape.
 */
data class TokenResponse(
	val accessToken: String,
	val refreshToken: String,
	val expiresInSeconds: Long,
	val tokenType: String = "Bearer",
)
```

- [ ] **Step 4: Create `JwtClaims`**

Create `libs/contracts/src/main/kotlin/com/floating/lyrics/contracts/JwtClaims.kt`:

```kotlin
package com.floating.lyrics.contracts

/**
 * Names of the claims the auth service puts in access-token JWTs, and the v1
 * scope placeholder. Both the issuer (auth) and any validating service (core)
 * reference these constants instead of hand-writing the strings.
 *
 * Standard claims (iss, sub, iat, exp, jti) keep their registered names and are
 * set via the JWT library, so only the custom claims live here.
 */
object JwtClaims {
	const val EMAIL = "email"
	const val SCOPES = "scopes"

	/** v1 placeholder scope; carries no enforcement until a real model exists. */
	const val SCOPE_APP_FULL = "app:full"
	val DEFAULT_SCOPES: List<String> = listOf(SCOPE_APP_FULL)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :libs:contracts:test --tests "com.floating.lyrics.contracts.JwtClaimsTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add libs/contracts/src
git commit -m "feat(contracts): extend TokenResponse and add JwtClaims constants"
```

### Task 4: Application configuration + typed properties

**Files:**
- Modify: `services/auth/src/main/resources/application.properties`
- Create: `auth/config/TokenProperties.kt`
- Create: `auth/config/KeyProperties.kt`
- Create: `auth/config/AppProperties.kt`
- Modify: `auth/AuthApplication.kt` (enable configuration-properties scanning)

- [ ] **Step 1: Write `application.properties`**

Replace `services/auth/src/main/resources/application.properties`:

```properties
spring.application.name=auth
server.port=8081

# --- Datasource: file-mode H2 so refresh tokens survive restarts ---
spring.datasource.url=jdbc:h2:file:./data/auth-db;AUTO_SERVER=TRUE
spring.datasource.username=sa
spring.datasource.password=
spring.datasource.driver-class-name=org.h2.Driver

# --- JPA: Flyway owns the schema; Hibernate must not touch DDL ---
spring.jpa.hibernate.ddl-auto=none
spring.jpa.open-in-view=false

# --- Flyway ---
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# --- Auth tokens ---
auth.token.issuer=floating-lyric-auth
auth.token.access-ttl=PT15M
auth.token.refresh-ttl=P30D

# --- Signing key (DEV ONLY key committed to the repo; never production) ---
auth.key.location=classpath:keys/dev-signing-key.pem

# --- Base URL used to build verification/reset links in emails ---
auth.base-url=http://localhost:8081
```

- [ ] **Step 2: Create the typed properties classes**

Create `auth/config/TokenProperties.kt`:

```kotlin
package com.floating.lyrics.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "auth.token")
data class TokenProperties(
	val issuer: String,
	val accessTtl: Duration,
	val refreshTtl: Duration,
)
```

Create `auth/config/KeyProperties.kt`:

```kotlin
package com.floating.lyrics.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth.key")
data class KeyProperties(
	/** Spring resource location of the PEM-encoded RSA private key. */
	val location: String,
)
```

Create `auth/config/AppProperties.kt`:

```kotlin
package com.floating.lyrics.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth")
data class AppProperties(
	/** Public base URL used to build verification/reset links in emails. */
	val baseUrl: String,
)
```

- [ ] **Step 3: Enable configuration-properties scanning**

Replace `auth/AuthApplication.kt`:

```kotlin
package com.floating.lyrics.auth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AuthApplication

fun main(args: Array<String>) {
	runApplication<AuthApplication>(*args)
}
```

- [ ] **Step 4: Commit (verification happens after the schema exists in Task 5)**

```bash
git add services/auth/src/main/resources/application.properties services/auth/src/main/kotlin/com/floating/lyrics/auth/config services/auth/src/main/kotlin/com/floating/lyrics/auth/AuthApplication.kt
git commit -m "feat(auth): datasource, flyway, and typed token/key config"
```

### Task 5: Dev signing key + database schema

**Files:**
- Create: `services/auth/src/main/resources/keys/dev-signing-key.pem`
- Create: `services/auth/src/main/resources/db/migration/V1__init_auth.sql`
- Create: `.gitignore` entry note for the H2 data dir

- [ ] **Step 1: Generate the dev RSA key (PKCS#8 PEM)**

Run:

```bash
mkdir -p services/auth/src/main/resources/keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out services/auth/src/main/resources/keys/dev-signing-key.pem
head -1 services/auth/src/main/resources/keys/dev-signing-key.pem
```

Expected: file created; first line is `-----BEGIN PRIVATE KEY-----`.

> This key is intentionally committed for local dev (the spec says so). It must never be used in production; production supplies its own key via `auth.key.location`.

- [ ] **Step 2: Ignore the H2 data directory**

Append to `services/auth/.gitignore` (the file already exists; create it if not):

```
# Local H2 database files
/data/
```

- [ ] **Step 3: Create the Flyway baseline migration**

Create `services/auth/src/main/resources/db/migration/V1__init_auth.sql`. Types are chosen to be valid on both H2 and PostgreSQL (forward-compat per spec):

```sql
CREATE TABLE users (
    id             UUID PRIMARY KEY,
    email          VARCHAR(320) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    display_name   VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at  TIMESTAMP WITH TIME ZONE,
    replaced_by UUID REFERENCES refresh_tokens (id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

CREATE TABLE email_verification_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens (user_id);

CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);
```

- [ ] **Step 4: Verify the context loads and Flyway applies the schema**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.AuthApplicationTests"`
Expected: PASS. The Spring context starts, Flyway runs V1 against H2, and `contextLoads()` succeeds.

> If it fails with a Flyway "no database support" error, confirm `flyway-database-h2` is a `runtimeOnly` dependency (Task 2). If it fails on the key, that is fine for this test (the key is only loaded by `KeyConfig`, added in Chunk 2) — but it should not, since no key bean exists yet.

- [ ] **Step 5: Commit**

```bash
git add services/auth/src/main/resources/keys services/auth/src/main/resources/db services/auth/.gitignore
git commit -m "feat(auth): dev signing key and V1 flyway schema"
```

---

## Chunk 2: Domain, persistence, token & key services, email

This chunk builds everything below the web layer: JPA entities + repositories, the key/JWT machinery, refresh-token rotation, the email stub, and the user/password services. Each unit is unit-tested in isolation (services that need persistence use `@DataJpaTest`; pure logic uses plain JUnit).

### Task 6: Entities and repositories

**Files:**
- Create: `auth/user/User.kt`, `auth/user/UserRepository.kt`
- Create: `auth/user/EmailVerificationToken.kt`, `auth/user/EmailVerificationTokenRepository.kt`
- Create: `auth/user/PasswordResetToken.kt`, `auth/user/PasswordResetTokenRepository.kt`
- Create: `auth/token/RefreshToken.kt`, `auth/token/RefreshTokenRepository.kt`
- Test: `authTest/user/UserRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository test**

Create `authTest/user/UserRepositoryTest.kt`:

```kotlin
package com.floating.lyrics.auth.user

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// replace = NONE is REQUIRED: @DataJpaTest otherwise swaps in an embedded DB and
// skips Flyway, so the V1 schema (and the `users` table) would never be created.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserRepositoryTest(@Autowired val users: UserRepository) {

	@Test
	fun `saves and finds a user by email (case-insensitive lookup expects lowercased storage)`() {
		val now = Instant.now()
		val saved = users.save(
			User(
				email = "jane@example.com",
				passwordHash = "hash",
				displayName = "Jane",
				emailVerified = false,
				createdAt = now,
				updatedAt = now,
			),
		)
		assertNotNull(saved.id)

		val found = users.findByEmail("jane@example.com")
		assertNotNull(found)
		assertEquals("Jane", found.displayName)
		assertTrue(users.existsByEmail("jane@example.com"))
		assertNull(users.findByEmail("nobody@example.com"))
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.user.UserRepositoryTest"`
Expected: FAIL — `User`/`UserRepository` unresolved.

- [ ] **Step 3: Create the `User` entity and repository**

Create `auth/user/User.kt`:

```kotlin
package com.floating.lyrics.auth.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
	@Column(nullable = false, unique = true)
	var email: String,

	@Column(name = "password_hash", nullable = false)
	var passwordHash: String,

	@Column(name = "display_name")
	var displayName: String? = null,

	@Column(name = "email_verified", nullable = false)
	var emailVerified: Boolean = false,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant,

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	var id: UUID? = null,
)
```

Create `auth/user/UserRepository.kt`:

```kotlin
package com.floating.lyrics.auth.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
	fun findByEmail(email: String): User?
	fun existsByEmail(email: String): Boolean
}
```

- [ ] **Step 4: Create the three token entities + repositories**

Create `auth/token/RefreshToken.kt`:

```kotlin
package com.floating.lyrics.auth.token

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
	@Column(name = "user_id", nullable = false)
	var userId: UUID,

	@Column(name = "token_hash", nullable = false, unique = true)
	var tokenHash: String,

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant,

	@Column(name = "revoked_at")
	var revokedAt: Instant? = null,

	@Column(name = "replaced_by")
	var replacedBy: UUID? = null,

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	var id: UUID? = null,
) {
	fun isActive(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)
}
```

Create `auth/token/RefreshTokenRepository.kt`:

```kotlin
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
```

Create `auth/user/EmailVerificationToken.kt`:

```kotlin
package com.floating.lyrics.auth.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "email_verification_tokens")
class EmailVerificationToken(
	@Column(name = "user_id", nullable = false)
	var userId: UUID,

	@Column(name = "token_hash", nullable = false, unique = true)
	var tokenHash: String,

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant,

	@Column(name = "consumed_at")
	var consumedAt: Instant? = null,

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	var id: UUID? = null,
)
```

Create `auth/user/EmailVerificationTokenRepository.kt`:

```kotlin
package com.floating.lyrics.auth.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EmailVerificationTokenRepository : JpaRepository<EmailVerificationToken, UUID> {
	fun findByTokenHash(tokenHash: String): EmailVerificationToken?
}
```

Create `auth/user/PasswordResetToken.kt` (identical shape, different table):

```kotlin
package com.floating.lyrics.auth.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "password_reset_tokens")
class PasswordResetToken(
	@Column(name = "user_id", nullable = false)
	var userId: UUID,

	@Column(name = "token_hash", nullable = false, unique = true)
	var tokenHash: String,

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant,

	@Column(name = "consumed_at")
	var consumedAt: Instant? = null,

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	var id: UUID? = null,
)
```

Create `auth/user/PasswordResetTokenRepository.kt`:

```kotlin
package com.floating.lyrics.auth.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, UUID> {
	fun findByTokenHash(tokenHash: String): PasswordResetToken?
}
```

- [ ] **Step 5: Run the repository test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.user.UserRepositoryTest"`
Expected: PASS.

> The `@AutoConfigureTestDatabase(replace = Replace.NONE)` annotation (added in Step 1) is what makes this pass: it keeps the configured H2 datasource so Flyway applies the V1 schema. Without it, `@DataJpaTest` would substitute an embedded DB and skip Flyway, and the `users` table would not exist.

- [ ] **Step 6: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/user services/auth/src/main/kotlin/com/floating/lyrics/auth/token services/auth/src/test/kotlin/com/floating/lyrics/auth/user
git commit -m "feat(auth): JPA entities and repositories"
```

### Task 7: Key configuration (signing key, encoder, decoder, JWK set)

`KeyConfig` is the only place that knows about Nimbus and key material. It exposes four beans: the `RSAKey` (private, with a stable `kid` = JWK thumbprint), a `JwtEncoder` (signs), a `JwtDecoder` (verifies — the single source of verification truth), and a `JWKSet` (public only, for the JWKS endpoint).

**Files:**
- Create: `auth/config/KeyConfig.kt`
- Test: `authTest/config/KeyConfigTest.kt`

- [ ] **Step 1: Write the failing test**

Create `authTest/config/KeyConfigTest.kt`:

```kotlin
package com.floating.lyrics.auth.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
class KeyConfigTest(
	@Autowired val encoder: JwtEncoder,
	@Autowired val decoder: JwtDecoder,
	@Autowired val jwkSet: com.nimbusds.jose.jwk.JWKSet,
) {

	@Test
	fun `a token signed by the encoder verifies with the decoder`() {
		val now = Instant.now()
		val claims = JwtClaimsSet.builder()
			.issuer("floating-lyric-auth")
			.subject("user-123")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(60))
			.build()
		val jwt = encoder.encode(JwtEncoderParameters.from(claims))

		val decoded = decoder.decode(jwt.tokenValue)
		assertEquals("user-123", decoded.subject)
	}

	@Test
	fun `jwk set exposes only public material`() {
		assertEquals(1, jwkSet.keys.size)
		assertTrue(jwkSet.keys.none { it.isPrivate })
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.config.KeyConfigTest"`
Expected: FAIL — no `JwtEncoder`/`JwtDecoder`/`JWKSet` beans.

- [ ] **Step 3: Implement `KeyConfig`**

Create `auth/config/KeyConfig.kt`:

```kotlin
package com.floating.lyrics.auth.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

@Configuration
class KeyConfig {

	/** Parse the PEM private key once; attach a stable kid (thumbprint) + RS256 metadata. */
	@Bean
	fun signingKey(keyProps: KeyProperties, resourceLoader: ResourceLoader): RSAKey {
		val pem = resourceLoader.getResource(keyProps.location).inputStream
			.bufferedReader().use { it.readText() }
		val parsed = JWK.parseFromPEMEncodedObjects(pem) as RSAKey
		val kid = parsed.computeThumbprint().toString()
		return RSAKey.Builder(parsed)
			.keyID(kid)
			.keyUse(KeyUse.SIGNATURE)
			.algorithm(JWSAlgorithm.RS256)
			.build()
	}

	@Bean
	fun jwkSet(signingKey: RSAKey): JWKSet = JWKSet(signingKey.toPublicJWK())

	@Bean
	fun jwtEncoder(signingKey: RSAKey): JwtEncoder =
		NimbusJwtEncoder(ImmutableJWKSet(JWKSet(signingKey)))

	@Bean
	fun jwtDecoder(signingKey: RSAKey, tokenProps: TokenProperties): JwtDecoder {
		val decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build()
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(tokenProps.issuer))
		return decoder
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.config.KeyConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/config/KeyConfig.kt services/auth/src/test/kotlin/com/floating/lyrics/auth/config
git commit -m "feat(auth): RSA signing key, JWT encoder/decoder, JWK set"
```

### Task 8: AccessTokenService

Mints access JWTs with the spec's claim set and validates them (delegating to the shared `JwtDecoder`).

**Files:**
- Create: `auth/token/AccessTokenService.kt`
- Test: `authTest/token/AccessTokenServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `authTest/token/AccessTokenServiceTest.kt`:

```kotlin
package com.floating.lyrics.auth.token

import com.floating.lyrics.contracts.JwtClaims
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
class AccessTokenServiceTest(
	@Autowired val service: AccessTokenService,
	@Autowired val decoder: JwtDecoder,
) {

	@Test
	fun `minted token carries sub, email, scopes and expiry`() {
		val userId = UUID.randomUUID()
		val minted = service.mint(userId, "jane@example.com")

		assertEquals(900, minted.expiresInSeconds) // PT15M

		val jwt = decoder.decode(minted.token)
		assertEquals(userId.toString(), jwt.subject)
		assertEquals("jane@example.com", jwt.getClaimAsString(JwtClaims.EMAIL))
		assertEquals(JwtClaims.DEFAULT_SCOPES, jwt.getClaimAsStringList(JwtClaims.SCOPES))
		assertEquals("floating-lyric-auth", jwt.issuer.toString())
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.token.AccessTokenServiceTest"`
Expected: FAIL — `AccessTokenService` unresolved.

- [ ] **Step 3: Implement `AccessTokenService`**

Create `auth/token/AccessTokenService.kt`:

```kotlin
package com.floating.lyrics.auth.token

import com.floating.lyrics.auth.config.TokenProperties
import com.floating.lyrics.contracts.JwtClaims
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Result of minting: the signed token plus its lifetime (for TokenResponse). */
data class MintedAccessToken(val token: String, val expiresInSeconds: Long)

@Service
class AccessTokenService(
	private val encoder: JwtEncoder,
	private val tokenProps: TokenProperties,
) {

	fun mint(userId: UUID, email: String): MintedAccessToken {
		val now = Instant.now()
		val expiresAt = now.plus(tokenProps.accessTtl)
		val claims = JwtClaimsSet.builder()
			.issuer(tokenProps.issuer)
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString()) // jti
			.claim(JwtClaims.EMAIL, email)
			.claim(JwtClaims.SCOPES, JwtClaims.DEFAULT_SCOPES)
			.build()
		val token = encoder.encode(JwtEncoderParameters.from(claims)).tokenValue
		return MintedAccessToken(token, tokenProps.accessTtl.seconds)
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.token.AccessTokenServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/token/AccessTokenService.kt services/auth/src/test/kotlin/com/floating/lyrics/auth/token/AccessTokenServiceTest.kt
git commit -m "feat(auth): access-token minting service"
```

### Task 9: TokenHasher + RefreshTokenService (issue / rotate / revoke)

Refresh tokens are opaque random strings returned to the client; only their SHA-256 hash is stored. `TokenHasher` is shared by refresh, verification, and reset tokens.

**Files:**
- Create: `auth/token/TokenHasher.kt`
- Create: `auth/token/RefreshTokenService.kt`
- Test: `authTest/token/TokenHasherTest.kt`
- Test: `authTest/token/RefreshTokenServiceTest.kt`

- [ ] **Step 1: Write the failing `TokenHasher` test**

Create `authTest/token/TokenHasherTest.kt`:

```kotlin
package com.floating.lyrics.auth.token

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TokenHasherTest {
	private val hasher = TokenHasher()

	@Test
	fun `generated tokens are url-safe and unique`() {
		val a = hasher.newToken()
		val b = hasher.newToken()
		assertNotEquals(a, b)
		assert(a.matches(Regex("[A-Za-z0-9_-]+"))) { "token must be url-safe: $a" }
	}

	@Test
	fun `hash is deterministic`() {
		assertEquals(hasher.hash("abc"), hasher.hash("abc"))
		assertNotEquals(hasher.hash("abc"), hasher.hash("abd"))
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.token.TokenHasherTest"`
Expected: FAIL — `TokenHasher` unresolved.

- [ ] **Step 3: Implement `TokenHasher`**

Create `auth/token/TokenHasher.kt`:

```kotlin
package com.floating.lyrics.auth.token

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Generates opaque random tokens and hashes them (SHA-256) for at-rest storage. */
@Component
class TokenHasher {
	private val random = SecureRandom()
	private val encoder = Base64.getUrlEncoder().withoutPadding()

	fun newToken(): String {
		val bytes = ByteArray(32)
		random.nextBytes(bytes)
		return encoder.encodeToString(bytes)
	}

	fun hash(token: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
		return encoder.encodeToString(digest)
	}
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.token.TokenHasherTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing `RefreshTokenService` test**

Create `authTest/token/RefreshTokenServiceTest.kt`:

```kotlin
package com.floating.lyrics.auth.token

import com.floating.lyrics.auth.error.InvalidTokenException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class RefreshTokenServiceTest(
	@Autowired val service: RefreshTokenService,
	@Autowired val repo: RefreshTokenRepository,
) {

	@Test
	fun `issues a token that can be validated once`() {
		val userId = UUID.randomUUID()
		val raw = service.issue(userId)
		assertNotNull(raw)

		val resolved = service.validateActive(raw)
		assertEquals(userId, resolved.userId)
	}

	@Test
	fun `rotation revokes the old token and returns a new one`() {
		val userId = UUID.randomUUID()
		val first = service.issue(userId)

		val rotated = service.rotate(first)
		assertNotEquals(first, rotated)

		// old token is no longer usable
		assertThrows<InvalidTokenException> { service.validateActive(first) }
		// new token works
		assertEquals(userId, service.validateActive(rotated).userId)
	}

	@Test
	fun `unknown token is rejected`() {
		assertThrows<InvalidTokenException> { service.validateActive("not-a-real-token") }
	}

	@Test
	fun `revokeAllForUser invalidates outstanding tokens`() {
		val userId = UUID.randomUUID()
		val raw = service.issue(userId)
		service.revokeAllForUser(userId)
		assertThrows<InvalidTokenException> { service.validateActive(raw) }
	}
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.token.RefreshTokenServiceTest"`
Expected: FAIL — `RefreshTokenService` and `InvalidTokenException` unresolved.

- [ ] **Step 7: Create the domain exceptions**

Create `auth/error/AuthExceptions.kt`:

```kotlin
package com.floating.lyrics.auth.error

/** 400/401 — a presented token (refresh/verify/reset) is missing, unknown, expired, or used. */
class InvalidTokenException(message: String = "Invalid or expired token") : RuntimeException(message)

/** 401 — bad email/password at login. */
class InvalidCredentialsException(message: String = "Invalid email or password") : RuntimeException(message)

/** 403 — login attempted before the email was verified. */
class EmailNotVerifiedException(message: String = "Email not verified") : RuntimeException(message)

/** 409 — registration with an email that already exists. */
class DuplicateEmailException(message: String = "Email already registered") : RuntimeException(message)
```

- [ ] **Step 8: Implement `RefreshTokenService`**

Create `auth/token/RefreshTokenService.kt`:

```kotlin
package com.floating.lyrics.auth.token

import com.floating.lyrics.auth.config.TokenProperties
import com.floating.lyrics.auth.error.InvalidTokenException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class RefreshTokenService(
	private val repo: RefreshTokenRepository,
	private val hasher: TokenHasher,
	private val tokenProps: TokenProperties,
) {

	/** Issue a new opaque refresh token for a user; returns the RAW token (shown once). */
	@Transactional
	fun issue(userId: UUID): String {
		val raw = hasher.newToken()
		val now = Instant.now()
		repo.save(
			RefreshToken(
				userId = userId,
				tokenHash = hasher.hash(raw),
				expiresAt = now.plus(tokenProps.refreshTtl),
				createdAt = now,
			),
		)
		return raw
	}

	/** Resolve a raw token to its active record, or throw. */
	@Transactional(readOnly = true)
	fun validateActive(raw: String): RefreshToken {
		val token = repo.findByTokenHash(hasher.hash(raw)) ?: throw InvalidTokenException()
		if (!token.isActive(Instant.now())) throw InvalidTokenException()
		return token
	}

	/** Rotate: validate the old token, revoke it, issue + link a new one. Returns the new RAW token. */
	@Transactional
	fun rotate(raw: String): String {
		val current = validateActive(raw)
		val now = Instant.now()
		val newRaw = hasher.newToken()
		val replacement = repo.save(
			RefreshToken(
				userId = current.userId,
				tokenHash = hasher.hash(newRaw),
				expiresAt = now.plus(tokenProps.refreshTtl),
				createdAt = now,
			),
		)
		current.revokedAt = now
		current.replacedBy = replacement.id
		repo.save(current)
		return newRaw
	}

	@Transactional
	fun revoke(raw: String) {
		val token = repo.findByTokenHash(hasher.hash(raw)) ?: return
		if (token.revokedAt == null) {
			token.revokedAt = Instant.now()
			repo.save(token)
		}
	}

	@Transactional
	fun revokeAllForUser(userId: UUID) {
		repo.revokeAllForUser(userId, Instant.now())
	}
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.token.RefreshTokenServiceTest"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/token services/auth/src/main/kotlin/com/floating/lyrics/auth/error services/auth/src/test/kotlin/com/floating/lyrics/auth/token
git commit -m "feat(auth): refresh-token issue/rotate/revoke + token hasher"
```

### Task 10: EmailSender stub

**Files:**
- Create: `auth/email/EmailSender.kt`
- Create: `auth/email/LoggingEmailSender.kt`

- [ ] **Step 1: Define the interface + logging implementation**

Create `auth/email/EmailSender.kt`:

```kotlin
package com.floating.lyrics.auth.email

/**
 * Outbound email. v1 ships only LoggingEmailSender; swapping in real SMTP touches
 * only this package.
 */
interface EmailSender {
	fun sendVerificationLink(to: String, link: String)
	fun sendPasswordResetLink(to: String, link: String)
}
```

Create `auth/email/LoggingEmailSender.kt`:

```kotlin
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
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :services:auth:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/email
git commit -m "feat(auth): logging email sender stub"
```

### Task 11: PasswordConfig + UserService (register / verify / resend)

**Files:**
- Create: `auth/security/PasswordConfig.kt`
- Create: `auth/user/UserService.kt`
- Test: `authTest/user/RecordingEmailSender.kt` (shared test double, reused by the password and controller tests)
- Test: `authTest/user/UserServiceTest.kt`

- [ ] **Step 0: Create the shared recording email sender**

This test double captures emitted links and is reused by `PasswordServiceTest` and the Chunk 3 controller tests. It lives in its own file (package `com.floating.lyrics.auth.user`) so those tests don't depend on another test's file.

Create `authTest/user/RecordingEmailSender.kt`:

```kotlin
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
```

- [ ] **Step 1: Write the failing test**

Create `authTest/user/UserServiceTest.kt` (note: `RecordingEmailSender` now comes from its own file, Step 0):

```kotlin
package com.floating.lyrics.auth.user

import com.floating.lyrics.auth.error.DuplicateEmailException
import com.floating.lyrics.auth.error.InvalidTokenException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class UserServiceTest(
	@Autowired val service: UserService,
	@Autowired val users: UserRepository,
	@Autowired val recorder: RecordingEmailSender,
) {

	@TestConfiguration
	class Config {
		@Bean
		@Primary
		fun recordingEmailSender() = RecordingEmailSender()
	}

	@Test
	fun `register stores a lowercased, unverified user and emails a verification link`() {
		service.register("Jane@Example.com", "password123", "Jane")

		val user = users.findByEmail("jane@example.com")
		assertNotNull(user)
		assertFalse(user.emailVerified)
		assertEquals(1, recorder.verifications.size)
		assertTrue(recorder.verifications.first().link.contains("token="))
	}

	@Test
	fun `duplicate registration is rejected`() {
		service.register("dup@example.com", "password123", null)
		assertThrows<DuplicateEmailException> {
			service.register("dup@example.com", "password123", null)
		}
	}

	@Test
	fun `verifying with the emailed token marks the user verified`() {
		service.register("v@example.com", "password123", null)
		val token = recorder.verifications.first().tokenParam()

		service.verifyEmail(token)

		assertTrue(users.findByEmail("v@example.com")!!.emailVerified)
	}

	@Test
	fun `verifying twice fails (token consumed)`() {
		service.register("v2@example.com", "password123", null)
		val token = recorder.verifications.first().tokenParam()
		service.verifyEmail(token)
		assertThrows<InvalidTokenException> { service.verifyEmail(token) }
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.user.UserServiceTest"`
Expected: FAIL — `UserService` unresolved.

- [ ] **Step 3: Create the `PasswordEncoder` bean**

Create `auth/security/PasswordConfig.kt`:

```kotlin
package com.floating.lyrics.auth.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
class PasswordConfig {
	@Bean
	fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
```

- [ ] **Step 4: Implement `UserService`**

Create `auth/user/UserService.kt`:

```kotlin
package com.floating.lyrics.auth.user

import com.floating.lyrics.auth.config.AppProperties
import com.floating.lyrics.auth.email.EmailSender
import com.floating.lyrics.auth.error.DuplicateEmailException
import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.token.TokenHasher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class UserService(
	private val users: UserRepository,
	private val verificationTokens: EmailVerificationTokenRepository,
	private val passwordEncoder: PasswordEncoder,
	private val hasher: TokenHasher,
	private val emailSender: EmailSender,
	private val appProps: AppProperties,
) {
	private val verificationTtl = Duration.ofHours(24)

	@Transactional
	fun register(email: String, rawPassword: String, displayName: String?) {
		val normalized = email.trim().lowercase()
		if (users.existsByEmail(normalized)) throw DuplicateEmailException()

		val now = Instant.now()
		val user = users.save(
			User(
				email = normalized,
				passwordHash = passwordEncoder.encode(rawPassword),
				displayName = displayName,
				emailVerified = false,
				createdAt = now,
				updatedAt = now,
			),
		)
		issueVerification(user)
	}

	@Transactional
	fun resendVerification(email: String) {
		val user = users.findByEmail(email.trim().lowercase()) ?: return // no enumeration
		if (user.emailVerified) return
		issueVerification(user)
	}

	@Transactional
	fun verifyEmail(rawToken: String) {
		val token = verificationTokens.findByTokenHash(hasher.hash(rawToken))
			?: throw InvalidTokenException()
		val now = Instant.now()
		if (token.consumedAt != null || token.expiresAt.isBefore(now)) throw InvalidTokenException()

		val user = users.findById(token.userId).orElseThrow { InvalidTokenException() }
		user.emailVerified = true
		user.updatedAt = now
		users.save(user)

		token.consumedAt = now
		verificationTokens.save(token)
	}

	private fun issueVerification(user: User) {
		val raw = hasher.newToken()
		verificationTokens.save(
			EmailVerificationToken(
				userId = user.id!!,
				tokenHash = hasher.hash(raw),
				expiresAt = Instant.now().plus(verificationTtl),
			),
		)
		val link = "${appProps.baseUrl}/auth/verify-email?token=$raw"
		emailSender.sendVerificationLink(user.email, link)
	}
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.user.UserServiceTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/security/PasswordConfig.kt services/auth/src/main/kotlin/com/floating/lyrics/auth/user/UserService.kt services/auth/src/test/kotlin/com/floating/lyrics/auth/user
git commit -m "feat(auth): user registration, verification, resend"
```

### Task 12: PasswordService (login, forgot, reset, change)

Owns credential checks and the password lifecycle. Login returns identity (the controller mints tokens); forgot/reset/change manage password state and session revocation.

**Files:**
- Create: `auth/password/PasswordService.kt`
- Test: `authTest/password/PasswordServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `authTest/password/PasswordServiceTest.kt`:

```kotlin
package com.floating.lyrics.auth.password

import com.floating.lyrics.auth.error.EmailNotVerifiedException
import com.floating.lyrics.auth.error.InvalidCredentialsException
import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.token.RefreshTokenService
import com.floating.lyrics.auth.user.RecordingEmailSender
import com.floating.lyrics.auth.user.UserRepository
import com.floating.lyrics.auth.user.UserService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class PasswordServiceTest(
	@Autowired val passwords: PasswordService,
	@Autowired val userService: UserService,
	@Autowired val users: UserRepository,
	@Autowired val encoder: PasswordEncoder,
	@Autowired val refreshTokens: RefreshTokenService,
	@Autowired val recorder: RecordingEmailSender,
) {
	@TestConfiguration
	class Config {
		@Bean @Primary fun recordingEmailSender() = RecordingEmailSender()
	}

	private fun registerVerified(email: String, pw: String) {
		userService.register(email, pw, null)
		val token = recorder.verifications.last().tokenParam()
		userService.verifyEmail(token)
	}

	@Test
	fun `login succeeds for a verified user with the right password`() {
		registerVerified("a@example.com", "password123")
		val user = passwords.authenticate("a@example.com", "password123")
		assertEquals("a@example.com", user.email)
	}

	@Test
	fun `login fails on wrong password`() {
		registerVerified("b@example.com", "password123")
		assertThrows<InvalidCredentialsException> { passwords.authenticate("b@example.com", "nope") }
	}

	@Test
	fun `login fails on unknown email with the same exception (no enumeration)`() {
		assertThrows<InvalidCredentialsException> { passwords.authenticate("ghost@example.com", "x") }
	}

	@Test
	fun `login blocked until email verified`() {
		userService.register("c@example.com", "password123", null)
		assertThrows<EmailNotVerifiedException> { passwords.authenticate("c@example.com", "password123") }
	}

	@Test
	fun `reset sets a new password, consumes the token, and revokes all sessions`() {
		registerVerified("d@example.com", "password123")
		val userId = users.findByEmail("d@example.com")!!.id!!
		val outstandingRefresh = refreshTokens.issue(userId) // an active session before reset

		passwords.forgot("d@example.com")
		val token = recorder.resets.last().tokenParam()

		passwords.reset(token, "newpassword1")

		val user = users.findByEmail("d@example.com")!!
		assertTrue(encoder.matches("newpassword1", user.passwordHash))
		// token is single-use
		assertThrows<InvalidTokenException> { passwords.reset(token, "another1234") }
		// reset revoked every outstanding refresh token (spec requirement)
		assertThrows<InvalidTokenException> { refreshTokens.validateActive(outstandingRefresh) }
	}

	@Test
	fun `change password requires the correct current password`() {
		registerVerified("e@example.com", "password123")
		val id = users.findByEmail("e@example.com")!!.id!!

		assertThrows<InvalidCredentialsException> { passwords.change(id, "wrong", "newpass1234") }
		passwords.change(id, "password123", "newpass1234")
		assertTrue(encoder.matches("newpass1234", users.findById(id).get().passwordHash))
	}

	@Test
	fun `forgot for unknown email is silent`() {
		passwords.forgot("nobody@example.com") // must not throw
		assertEquals(0, recorder.resets.size)
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.password.PasswordServiceTest"`
Expected: FAIL — `PasswordService` unresolved.

- [ ] **Step 3: Implement `PasswordService`**

Create `auth/password/PasswordService.kt`:

```kotlin
package com.floating.lyrics.auth.password

import com.floating.lyrics.auth.config.AppProperties
import com.floating.lyrics.auth.email.EmailSender
import com.floating.lyrics.auth.error.EmailNotVerifiedException
import com.floating.lyrics.auth.error.InvalidCredentialsException
import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.token.RefreshTokenService
import com.floating.lyrics.auth.token.TokenHasher
import com.floating.lyrics.auth.user.PasswordResetToken
import com.floating.lyrics.auth.user.PasswordResetTokenRepository
import com.floating.lyrics.auth.user.User
import com.floating.lyrics.auth.user.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class PasswordService(
	private val users: UserRepository,
	private val resetTokens: PasswordResetTokenRepository,
	private val refreshTokens: RefreshTokenService,
	private val passwordEncoder: PasswordEncoder,
	private val hasher: TokenHasher,
	private val emailSender: EmailSender,
	private val appProps: AppProperties,
) {
	private val resetTtl = Duration.ofHours(1)

	/** Verify credentials; returns the user or throws. Blocks unverified accounts. */
	@Transactional(readOnly = true)
	fun authenticate(email: String, rawPassword: String): User {
		val user = users.findByEmail(email.trim().lowercase())
			?: throw InvalidCredentialsException()
		if (!passwordEncoder.matches(rawPassword, user.passwordHash)) {
			throw InvalidCredentialsException()
		}
		if (!user.emailVerified) throw EmailNotVerifiedException()
		return user
	}

	@Transactional
	fun forgot(email: String) {
		val user = users.findByEmail(email.trim().lowercase()) ?: return // no enumeration
		val raw = hasher.newToken()
		resetTokens.save(
			PasswordResetToken(
				userId = user.id!!,
				tokenHash = hasher.hash(raw),
				expiresAt = Instant.now().plus(resetTtl),
			),
		)
		emailSender.sendPasswordResetLink(user.email, "${appProps.baseUrl}/auth/password/reset?token=$raw")
	}

	@Transactional
	fun reset(rawToken: String, newPassword: String) {
		val token = resetTokens.findByTokenHash(hasher.hash(rawToken)) ?: throw InvalidTokenException()
		val now = Instant.now()
		if (token.consumedAt != null || token.expiresAt.isBefore(now)) throw InvalidTokenException()

		val user = users.findById(token.userId).orElseThrow { InvalidTokenException() }
		user.passwordHash = passwordEncoder.encode(newPassword)
		user.updatedAt = now
		users.save(user)

		token.consumedAt = now
		resetTokens.save(token)

		refreshTokens.revokeAllForUser(user.id!!) // reset invalidates all sessions
	}

	@Transactional
	fun change(userId: UUID, currentPassword: String, newPassword: String) {
		val user = users.findById(userId).orElseThrow { InvalidCredentialsException() }
		if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
			throw InvalidCredentialsException()
		}
		user.passwordHash = passwordEncoder.encode(newPassword)
		user.updatedAt = Instant.now()
		users.save(user)
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.password.PasswordServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/password services/auth/src/test/kotlin/com/floating/lyrics/auth/password
git commit -m "feat(auth): authenticate, forgot, reset, change-password"
```

---

## Chunk 3: Web layer, security, and end-to-end tests

This chunk exposes the HTTP API, wires Spring Security (public vs Bearer-protected routes via resource-server JWT support), maps errors to the `{error, message}` body, and verifies every endpoint with MockMvc integration tests.

### Task 13: Request/response DTOs + error model

**Files:**
- Create: `auth/web/dto/AuthDtos.kt`
- Create: `auth/web/ApiError.kt`
- Create: `auth/web/ApiExceptionHandler.kt`

- [ ] **Step 1: Create DTOs**

Create `auth/web/dto/AuthDtos.kt` (validation annotations apply to fields via the project's `-Xannotation-default-target=param-property` compiler flag):

```kotlin
package com.floating.lyrics.auth.web.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

private const val MIN_PASSWORD = 8

data class RegisterRequest(
	@field:Email @field:NotBlank val email: String,
	@field:Size(min = MIN_PASSWORD, message = "password must be at least 8 characters") val password: String,
	val displayName: String? = null,
)

data class LoginRequest(
	@field:Email @field:NotBlank val email: String,
	@field:NotBlank val password: String,
)

data class EmailOnlyRequest(
	@field:Email @field:NotBlank val email: String,
)

data class TokenOnlyRequest(
	@field:NotBlank val token: String,
)

data class RefreshRequest(
	@field:NotBlank val refreshToken: String,
)

data class ResetPasswordRequest(
	@field:NotBlank val token: String,
	@field:Size(min = MIN_PASSWORD, message = "password must be at least 8 characters") val newPassword: String,
)

data class ChangePasswordRequest(
	@field:NotBlank val oldPassword: String,
	@field:Size(min = MIN_PASSWORD, message = "password must be at least 8 characters") val newPassword: String,
)

data class MeResponse(
	val id: String,
	val email: String,
	val displayName: String?,
	val emailVerified: Boolean,
)
```

- [ ] **Step 2: Create the error body + handler**

Create `auth/web/ApiError.kt`:

```kotlin
package com.floating.lyrics.auth.web

data class ApiError(val error: String, val message: String)
```

Create `auth/web/ApiExceptionHandler.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.error.DuplicateEmailException
import com.floating.lyrics.auth.error.EmailNotVerifiedException
import com.floating.lyrics.auth.error.InvalidCredentialsException
import com.floating.lyrics.auth.error.InvalidTokenException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun onValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
		val msg = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
		return body(HttpStatus.BAD_REQUEST, "validation_failed", msg.ifBlank { "Invalid request" })
	}

	@ExceptionHandler(InvalidCredentialsException::class)
	fun onBadCreds(ex: InvalidCredentialsException) =
		body(HttpStatus.UNAUTHORIZED, "invalid_credentials", ex.message ?: "Invalid email or password")

	@ExceptionHandler(EmailNotVerifiedException::class)
	fun onUnverified(ex: EmailNotVerifiedException) =
		body(HttpStatus.FORBIDDEN, "email_not_verified", ex.message ?: "Email not verified")

	@ExceptionHandler(DuplicateEmailException::class)
	fun onDuplicate(ex: DuplicateEmailException) =
		body(HttpStatus.CONFLICT, "email_taken", ex.message ?: "Email already registered")

	@ExceptionHandler(InvalidTokenException::class)
	fun onBadToken(ex: InvalidTokenException) =
		body(HttpStatus.BAD_REQUEST, "invalid_token", ex.message ?: "Invalid or expired token")

	private fun body(status: HttpStatus, error: String, message: String) =
		ResponseEntity.status(status).body(ApiError(error, message))
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :services:auth:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/web
git commit -m "feat(auth): request/response DTOs and error handling"
```

### Task 14: Security configuration

Public auth endpoints + JWKS are open; `/auth/me` and `/auth/password/change` require a valid Bearer JWT, validated by the shared `JwtDecoder` (resource-server).

**Files:**
- Create: `auth/security/SecurityConfig.kt`

- [ ] **Step 1: Implement `SecurityConfig`**

Create `auth/security/SecurityConfig.kt`:

```kotlin
package com.floating.lyrics.auth.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig {

	@Bean
	fun filterChain(http: HttpSecurity): SecurityFilterChain {
		http {
			csrf { disable() }
			sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
			authorizeHttpRequests {
				authorize(HttpMethod.GET, "/.well-known/jwks.json", permitAll)
				authorize("/auth/me", authenticated)
				authorize("/auth/password/change", authenticated)
				authorize("/auth/**", permitAll)
				authorize(anyRequest, authenticated)
			}
			oauth2ResourceServer { jwt { } } // uses the JwtDecoder bean from KeyConfig
		}
		return http.build()
	}
}
```

- [ ] **Step 2: Verify it compiles and the full context still loads**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.AuthApplicationTests"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/security/SecurityConfig.kt
git commit -m "feat(auth): security config with public + bearer-protected routes"
```

### Task 15: AuthController (register, verify, resend, login, refresh, logout) + integration tests

**Files:**
- Create: `auth/web/AuthController.kt`
- Test: `authTest/web/AuthControllerTest.kt`

- [ ] **Step 1: Write the failing integration test**

Create `authTest/web/AuthControllerTest.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.floating.lyrics.auth.user.RecordingEmailSender
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AuthControllerTest(
	@Autowired val mvc: MockMvc,
	@Autowired val json: ObjectMapper,
	@Autowired val recorder: RecordingEmailSender,
) {
	@TestConfiguration
	class Config {
		@Bean @Primary fun recordingEmailSender() = RecordingEmailSender()
	}

	private fun register(email: String, pw: String = "password123") =
		mvc.post("/auth/register") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to email, "password" to pw, "displayName" to "T"))
		}

	private fun verifyLatest() {
		val token = recorder.verifications.last().link.substringAfter("token=")
		mvc.post("/auth/verify-email") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token))
		}.andExpect { status { isOk() } }
	}

	@Test
	fun `register returns 201 and emails a verification link`() {
		register("reg@example.com").andExpect { status { isCreated() } }
		assertNotNull(recorder.verifications.lastOrNull())
	}

	@Test
	fun `register with short password returns 400`() {
		register("short@example.com", "x").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `duplicate register returns 409`() {
		register("dupe@example.com").andExpect { status { isCreated() } }
		register("dupe@example.com").andExpect { status { isConflict() } }
	}

	@Test
	fun `login before verification returns 403`() {
		register("unv@example.com").andExpect { status { isCreated() } }
		mvc.post("/auth/login") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "unv@example.com", "password" to "password123"))
		}.andExpect { status { isForbidden() } }
	}

	@Test
	fun `verify then login returns tokens; refresh rotates; old refresh rejected`() {
		register("flow@example.com").andExpect { status { isCreated() } }
		verifyLatest()

		val loginBody = mvc.post("/auth/login") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "flow@example.com", "password" to "password123"))
		}.andExpect {
			status { isOk() }
			jsonPath("$.accessToken") { exists() }
			jsonPath("$.refreshToken") { exists() }
			jsonPath("$.tokenType") { value("Bearer") }
		}.andReturn().response.contentAsString

		val refresh1 = json.readTree(loginBody).get("refreshToken").asText()

		val refreshBody = mvc.post("/auth/refresh") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("refreshToken" to refresh1))
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val refresh2 = json.readTree(refreshBody).get("refreshToken").asText()

		// old refresh token no longer works
		mvc.post("/auth/refresh") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("refreshToken" to refresh1))
		}.andExpect { status { isBadRequest() } }

		// logout with the current token succeeds
		mvc.post("/auth/logout") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("refreshToken" to refresh2))
		}.andExpect { status { isNoContent() } }
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.web.AuthControllerTest"`
Expected: FAIL — `AuthController` does not exist (404s / context errors).

- [ ] **Step 3: Implement `AuthController`**

Create `auth/web/AuthController.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.password.PasswordService
import com.floating.lyrics.auth.token.AccessTokenService
import com.floating.lyrics.auth.token.RefreshTokenService
import com.floating.lyrics.auth.user.UserService
import com.floating.lyrics.auth.web.dto.EmailOnlyRequest
import com.floating.lyrics.auth.web.dto.LoginRequest
import com.floating.lyrics.auth.web.dto.RefreshRequest
import com.floating.lyrics.auth.web.dto.RegisterRequest
import com.floating.lyrics.auth.web.dto.TokenOnlyRequest
import com.floating.lyrics.contracts.TokenResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
	private val userService: UserService,
	private val passwordService: PasswordService,
	private val accessTokens: AccessTokenService,
	private val refreshTokens: RefreshTokenService,
) {

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	fun register(@Valid @RequestBody req: RegisterRequest) {
		userService.register(req.email, req.password, req.displayName)
	}

	@PostMapping("/verify-email")
	fun verifyEmail(@Valid @RequestBody req: TokenOnlyRequest) {
		userService.verifyEmail(req.token)
	}

	@PostMapping("/resend-verification")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun resendVerification(@Valid @RequestBody req: EmailOnlyRequest) {
		userService.resendVerification(req.email)
	}

	@PostMapping("/login")
	fun login(@Valid @RequestBody req: LoginRequest): TokenResponse {
		val user = passwordService.authenticate(req.email, req.password)
		return issueTokens(user.id!!, user.email)
	}

	@PostMapping("/refresh")
	fun refresh(@Valid @RequestBody req: RefreshRequest): TokenResponse {
		val current = refreshTokens.validateActive(req.refreshToken)
		val newRefresh = refreshTokens.rotate(req.refreshToken)
		val access = accessTokens.mint(current.userId, emailFor(current.userId))
		return TokenResponse(access.token, newRefresh, access.expiresInSeconds)
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun logout(@Valid @RequestBody req: RefreshRequest) {
		refreshTokens.revoke(req.refreshToken)
	}

	private fun issueTokens(userId: java.util.UUID, email: String): TokenResponse {
		val access = accessTokens.mint(userId, email)
		val refresh = refreshTokens.issue(userId)
		return TokenResponse(access.token, refresh, access.expiresInSeconds)
	}

	// email is needed for the access-token claim on refresh; look it up via the user service.
	private fun emailFor(userId: java.util.UUID): String = userService.emailFor(userId)
}
```

Add a small lookup to `UserService` (so the controller does not touch the repository directly). Append to `auth/user/UserService.kt`:

```kotlin
	@Transactional(readOnly = true)
	fun emailFor(userId: java.util.UUID): String =
		users.findById(userId).orElseThrow { com.floating.lyrics.auth.error.InvalidTokenException() }.email
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.web.AuthControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/web/AuthController.kt services/auth/src/main/kotlin/com/floating/lyrics/auth/user/UserService.kt services/auth/src/test/kotlin/com/floating/lyrics/auth/web/AuthControllerTest.kt
git commit -m "feat(auth): auth controller (register/verify/login/refresh/logout)"
```

### Task 16: PasswordController (forgot, reset, change) + tests

`forgot` always returns 202 (no enumeration). `change` reads the user id from the Bearer JWT subject.

**Files:**
- Create: `auth/web/PasswordController.kt`
- Test: `authTest/web/PasswordControllerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `authTest/web/PasswordControllerTest.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.floating.lyrics.auth.user.RecordingEmailSender
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import com.floating.lyrics.auth.user.UserRepository

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PasswordControllerTest(
	@Autowired val mvc: MockMvc,
	@Autowired val json: ObjectMapper,
	@Autowired val recorder: RecordingEmailSender,
	@Autowired val users: UserRepository,
) {
	@TestConfiguration
	class Config {
		@Bean @Primary fun recordingEmailSender() = RecordingEmailSender()
	}

	private fun registerVerified(email: String) {
		mvc.post("/auth/register") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to email, "password" to "password123"))
		}.andExpect { status { isCreated() } }
		val token = recorder.verifications.last().link.substringAfter("token=")
		mvc.post("/auth/verify-email") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token))
		}.andExpect { status { isOk() } }
	}

	@Test
	fun `forgot returns 202 even for unknown email`() {
		mvc.post("/auth/password/forgot") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "ghost@example.com"))
		}.andExpect { status { isAccepted() } }
	}

	@Test
	fun `reset with the emailed token returns 200 then fails on reuse`() {
		registerVerified("reset@example.com")
		mvc.post("/auth/password/forgot") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "reset@example.com"))
		}.andExpect { status { isAccepted() } }
		val token = recorder.resets.last().link.substringAfter("token=")

		mvc.post("/auth/password/reset") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token, "newPassword" to "brandnew123"))
		}.andExpect { status { isOk() } }

		mvc.post("/auth/password/reset") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token, "newPassword" to "another12345"))
		}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `change requires authentication`() {
		mvc.post("/auth/password/change") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("oldPassword" to "password123", "newPassword" to "newpass12345"))
		}.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `change with a valid bearer token and correct current password returns 200`() {
		registerVerified("chg@example.com")
		val id = users.findByEmail("chg@example.com")!!.id!!.toString()

		mvc.post("/auth/password/change") {
			with(jwt().jwt { it.subject(id).claim("email", "chg@example.com") })
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("oldPassword" to "password123", "newPassword" to "newpass12345"))
		}.andExpect { status { isOk() } }
	}
}
```

> Note: the authenticated happy-path for `change` is covered at the service layer in Task 12 (`PasswordServiceTest`); the controller test above asserts the security gate (401 without a token). A `jwt()`-authenticated 200 case can be added once a real user id is available — keep it simple and rely on the service test for the success path.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.web.PasswordControllerTest"`
Expected: FAIL — `PasswordController` does not exist.

- [ ] **Step 3: Implement `PasswordController`**

Create `auth/web/PasswordController.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.password.PasswordService
import com.floating.lyrics.auth.web.dto.ChangePasswordRequest
import com.floating.lyrics.auth.web.dto.EmailOnlyRequest
import com.floating.lyrics.auth.web.dto.ResetPasswordRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth/password")
class PasswordController(
	private val passwordService: PasswordService,
) {

	@PostMapping("/forgot")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun forgot(@Valid @RequestBody req: EmailOnlyRequest) {
		passwordService.forgot(req.email)
	}

	@PostMapping("/reset")
	fun reset(@Valid @RequestBody req: ResetPasswordRequest) {
		passwordService.reset(req.token, req.newPassword)
	}

	@PostMapping("/change")
	fun change(
		@AuthenticationPrincipal jwt: Jwt,
		@Valid @RequestBody req: ChangePasswordRequest,
	) {
		passwordService.change(UUID.fromString(jwt.subject), req.oldPassword, req.newPassword)
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.web.PasswordControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/web/PasswordController.kt services/auth/src/test/kotlin/com/floating/lyrics/auth/web/PasswordControllerTest.kt
git commit -m "feat(auth): password controller (forgot/reset/change)"
```

### Task 17: AccountController (/me) + JwksController + tests

**Files:**
- Create: `auth/web/AccountController.kt`
- Create: `auth/web/JwksController.kt`
- Test: `authTest/web/AccountAndJwksTest.kt`

- [ ] **Step 1: Write the failing test**

Create `authTest/web/AccountAndJwksTest.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.floating.lyrics.auth.user.RecordingEmailSender
import com.floating.lyrics.auth.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AccountAndJwksTest(
	@Autowired val mvc: MockMvc,
	@Autowired val json: ObjectMapper,
	@Autowired val recorder: RecordingEmailSender,
	@Autowired val users: UserRepository,
) {
	@TestConfiguration
	class Config {
		@Bean @Primary fun recordingEmailSender() = RecordingEmailSender()
	}

	@Test
	fun `jwks endpoint is public and exposes one public key`() {
		mvc.get("/.well-known/jwks.json").andExpect {
			status { isOk() }
			jsonPath("$.keys.length()") { value(1) }
			jsonPath("$.keys[0].kty") { value("RSA") }
			jsonPath("$.keys[0].d") { doesNotExist() } // no private material
		}
	}

	@Test
	fun `me requires a token`() {
		mvc.get("/auth/me").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `me returns the caller's profile`() {
		// create a verified user, then mint a JWT for it via the resource-server test support
		mvc.post("/auth/register") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("email" to "me@example.com", "password" to "password123"))
		}.andExpect { status { isCreated() } }
		val token = recorder.verifications.last().link.substringAfter("token=")
		mvc.post("/auth/verify-email") {
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(mapOf("token" to token))
		}
		val id = users.findByEmail("me@example.com")!!.id!!.toString()

		mvc.get("/auth/me") {
			with(jwt().jwt { it.subject(id).claim("email", "me@example.com") })
		}.andExpect {
			status { isOk() }
			jsonPath("$.email") { value("me@example.com") }
			jsonPath("$.emailVerified") { value(true) }
		}
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.web.AccountAndJwksTest"`
Expected: FAIL — controllers don't exist.

- [ ] **Step 3: Implement the controllers**

Create `auth/web/JwksController.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.nimbusds.jose.jwk.JWKSet
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class JwksController(private val jwkSet: JWKSet) {

	/** Public keys for validators. jwkSet already contains only public material. */
	@GetMapping("/.well-known/jwks.json")
	fun jwks(): Map<String, Any> = jwkSet.toJSONObject()
}
```

Create `auth/web/AccountController.kt`:

```kotlin
package com.floating.lyrics.auth.web

import com.floating.lyrics.auth.error.InvalidTokenException
import com.floating.lyrics.auth.user.UserRepository
import com.floating.lyrics.auth.web.dto.MeResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth")
class AccountController(private val users: UserRepository) {

	@GetMapping("/me")
	fun me(@AuthenticationPrincipal jwt: Jwt): MeResponse {
		val user = users.findById(UUID.fromString(jwt.subject))
			.orElseThrow { InvalidTokenException("Unknown subject") }
		return MeResponse(
			id = user.id!!.toString(),
			email = user.email,
			displayName = user.displayName,
			emailVerified = user.emailVerified,
		)
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :services:auth:test --tests "com.floating.lyrics.auth.web.AccountAndJwksTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/auth/src/main/kotlin/com/floating/lyrics/auth/web/JwksController.kt services/auth/src/main/kotlin/com/floating/lyrics/auth/web/AccountController.kt services/auth/src/test/kotlin/com/floating/lyrics/auth/web/AccountAndJwksTest.kt
git commit -m "feat(auth): /me account endpoint and JWKS endpoint"
```

### Task 18: Full build, manual smoke test, and docs

**Files:**
- Modify: `README.md` (note auth endpoints + dev key caveat)

- [ ] **Step 1: Run the entire module build (all tests)**

Run: `./gradlew :services:auth:build`
Expected: BUILD SUCCESSFUL; all tests green.

- [ ] **Step 2: Run the whole repo build (nothing else broke)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL across `libs:contracts`, `services:core`, `services:auth`.

- [ ] **Step 3: Manual smoke test (optional but recommended)**

Start the service:

```bash
./gradlew :services:auth:bootRun
```

In another shell:

```bash
# register
curl -s -i -X POST localhost:8081/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"smoke@example.com","password":"password123","displayName":"Smoke"}'
# -> 201; grab the verification link from the bootRun log line "[email:verify] ... link=..."

# JWKS is public
curl -s localhost:8081/.well-known/jwks.json
```

Expected: `register` returns 201; the log prints a `[email:verify]` line with a `token=` link; the JWKS endpoint returns one public RSA key.

Stop the service (Ctrl+C). Note: H2 wrote `services/auth/data/` (git-ignored).

- [ ] **Step 4: Document the endpoints in the README**

Add a short "Auth service" subsection under the module table or Common tasks in `README.md` listing the endpoints (from the spec's API table) and a one-line warning that `dev-signing-key.pem` is a committed dev-only key, never for production.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: document auth service endpoints and dev key caveat"
```

---

## Done criteria

- `./gradlew build` is green.
- All endpoints in the spec's API table exist with the specified status codes.
- Access tokens are RS256 JWTs verifiable via `/.well-known/jwks.json`; refresh tokens rotate and the previous token is rejected after use.
- Unverified users get 403 on login; password reset revokes all sessions; account-enumeration-safe endpoints always return 2xx.
- Email links are emitted via `LoggingEmailSender` (visible in logs / captured in tests).
- Deferred items (social, brute-force, SMTP, Postgres, scope enforcement, core wiring) remain out, as documented in the spec.
