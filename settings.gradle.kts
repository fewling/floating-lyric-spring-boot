rootProject.name = "floating-lyric"

// Convention plugins (shared build config) live in build-logic/.
// Including the build here makes plugins like "floating-lyric.spring-service"
// available to every module below.
pluginManagement {
	includeBuild("build-logic")
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
}

// Shared libraries — plain jars that services compile against.
include("libs:contracts")

// Deployable services — each builds its own Spring Boot jar and runs on its own.
include("services:core")
include("services:auth")
