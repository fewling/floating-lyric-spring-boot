// Convention plugin for a plain shared LIBRARY (no Spring Boot app / no fat jar).
// Services compile against these. Apply with: id("floating-lyric.kotlin-library")
plugins {
	kotlin("jvm")
}

group = "com.floating.lyrics"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	"testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5")
	"testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
