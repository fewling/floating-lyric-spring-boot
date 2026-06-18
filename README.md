<div align="center">

# Floating Lyric

A Spring Boot **microservices** backend, organized as a single Gradle multi-module monorepo.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Gradle](https://img.shields.io/badge/Gradle-9.5.1-02303A?logo=gradle&logoColor=white)](https://gradle.org/)

</div>

## Overview

Floating Lyric is a backend made of small, independently deployable **services** that share common code through plain **library** modules. Everything lives in one repository ("monorepo") so that build configuration and shared contracts stay in sync — but each service still builds its own runnable jar and is deployed on its own.

| Module | Type | Description |
| --- | --- | --- |
| [`services/core`](services/core) | Service | Core application service. |
| [`services/auth`](services/auth) | Service | Authentication service. |
| [`libs/contracts`](libs/contracts) | Library | Shared DTOs / API models that cross service boundaries. |
| [`build-logic`](build-logic) | Build logic | Convention plugins holding the shared Gradle setup. |

> [!NOTE]
> Services communicate with each other **over the network** (HTTP/gRPC/messaging), not by depending on each other's code. The only thing they share at compile time is library modules under `libs/`.

## Tech stack

- **Language:** Kotlin 2.3.21 (JVM), targeting the Java 25 toolchain
- **Framework:** Spring Boot 4.1.0
- **Build:** Gradle 9.5.1 with the Kotlin DSL and a wrapper (no local Gradle install needed)
- **Testing:** JUnit 5 + Spring Boot Test

## Prerequisites

- **JDK 25** (the build's Java toolchain). Verify with `java -version`.
- That's it — the included Gradle wrapper (`./gradlew`) downloads the correct Gradle version automatically.

## Getting started

```bash
# Build and test every module
./gradlew build

# Run a single service
./gradlew :services:core:bootRun
./gradlew :services:auth:bootRun
```

> [!IMPORTANT]
> Both services default to port **8080**, so they can't run at the same time out of the box. Give one a different port before running them together, e.g.:
> ```bash
> ./gradlew :services:auth:bootRun --args='--server.port=8081'
> ```
> or set `server.port` in that service's `src/main/resources/application.properties`.

## Project structure

```
floating-lyric-spring-boot/
├── build-logic/                 # Shared Gradle build logic (convention plugins)
│   └── src/main/kotlin/
│       ├── floating-lyric.spring-service.gradle.kts   # config for runnable services
│       └── floating-lyric.kotlin-library.gradle.kts   # config for shared libraries
├── libs/                        # Shared libraries (plain jars services compile against)
│   └── contracts/
├── services/                    # Deployable Spring Boot services
│   ├── auth/
│   └── core/
├── gradle/                      # Gradle wrapper
├── settings.gradle.kts          # Lists every module in the build
└── gradlew / gradlew.bat
```

### How the build stays DRY

Shared configuration (Kotlin, Spring Boot, the Java toolchain, test setup) is defined **once** in [`build-logic`](build-logic) as *convention plugins*, so module build files stay tiny:

```kotlin
// services/auth/build.gradle.kts
plugins {
    id("floating-lyric.spring-service")
}

dependencies {
    implementation(project(":libs:contracts")) // depend on a shared library
}
```

Bumping a shared version (Kotlin, Spring Boot, Java) is a one-line change in the convention plugin and applies to every module.

## Common tasks

| Command | Description |
| --- | --- |
| `./gradlew build` | Compile and test all modules |
| `./gradlew test` | Run all tests |
| `./gradlew :services:core:bootRun` | Run the **core** service |
| `./gradlew :services:auth:bootRun` | Run the **auth** service |
| `./gradlew :services:auth:bootJar` | Build a runnable jar for **auth** |
| `./gradlew projects` | Print the module tree |

## Auth service

`services/auth` is a standalone Spring Boot service providing user registration, email verification, login, and JWT-based session management.

| Endpoint | Auth | Request body | Success | Notes |
| --- | --- | --- | --- | --- |
| `POST /auth/register` | none | `{email, password, displayName?}` | `201` | Creates an unverified user; sends a verification email. |
| `POST /auth/verify-email` | none | `{token}` | `200` | Marks the user verified; consumes the token. |
| `POST /auth/resend-verification` | none | `{email}` | `202` | Always `202` (no account enumeration). |
| `POST /auth/login` | none | `{email, password}` | `200` | Returns `TokenResponse`; `403` if email not verified. |
| `POST /auth/refresh` | none | `{refreshToken}` | `200` | Rotates the refresh token (`TokenResponse`). |
| `POST /auth/logout` | none | `{refreshToken}` | `204` | Revokes the presented refresh token. |
| `POST /auth/password/forgot` | none | `{email}` | `202` | Sends a reset link. Always `202` (no enumeration). |
| `POST /auth/password/reset` | none | `{token, newPassword}` | `200` | Sets new password; revokes all refresh tokens. |
| `POST /auth/password/change` | Bearer | `{oldPassword, newPassword}` | `200` | Authenticated self-service change. |
| `GET /auth/me` | Bearer | — | `200` | Returns `{id, email, displayName, emailVerified}`. |
| `GET /.well-known/jwks.json` | none | — | `200` | Public RSA key set for validators. |

Access tokens are RS256 JWTs; public keys are exposed via the JWKS endpoint so any other service can verify them without a shared secret.

> [!WARNING]
> `services/auth/src/main/resources/keys/dev-signing-key.pem` is a committed **DEV-ONLY** RSA key for local development. Never use it in production — generate and inject a fresh private key via environment variable or secrets manager before deploying.

## Adding a new service

1. Create the folder and a minimal build file:
   ```kotlin
   // services/<name>/build.gradle.kts
   plugins {
       id("floating-lyric.spring-service")
   }
   ```
2. Add your `@SpringBootApplication` class under `services/<name>/src/main/kotlin/...`.
3. Register the module in [`settings.gradle.kts`](settings.gradle.kts):
   ```kotlin
   include("services:<name>")
   ```

Add a shared library the same way using the `floating-lyric.kotlin-library` plugin under `libs/`.

> [!TIP]
> Put models that more than one service needs (request/response DTOs, events) in [`libs/contracts`](libs/contracts) so callers don't hand-roll their own copies.

## Part of the Floating Lyric system

This repo is one of several that make up Floating Lyric:

- 📱 App — [flutter-floating-lyric](https://github.com/fewling/flutter-floating-lyric)
- 🌐 Landing — [floating-lyric-web](https://github.com/fewling/floating-lyric-web)
- 🔥 Firebase backend — [flutter-floating-lyric-firebase-cloud-function](https://github.com/fewling/flutter-floating-lyric-firebase-cloud-function)
- 🌱 Spring Boot backend — [floating-lyric-spring-boot](https://github.com/fewling/floating-lyric-spring-boot)  ← this repo
- 📄 API contract — [flutter-floating-lyric-openapi](https://github.com/fewling/flutter-floating-lyric-openapi)
- 📦 Generated DTOs — [flutter-floating-lyric-pkg-generated-openapi](https://github.com/fewling/flutter-floating-lyric-pkg-generated-openapi)

📋 Work across all repos is tracked on the [Floating Lyric — Product board](https://github.com/users/fewling/projects/2).
