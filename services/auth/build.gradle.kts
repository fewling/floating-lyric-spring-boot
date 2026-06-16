plugins {
	id("floating-lyric.spring-service")
}

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
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("com.h2database:h2")

	// Security test helpers (spring-security-test) for MockMvc with JWTs.
	testImplementation("org.springframework.security:spring-security-test")
}
