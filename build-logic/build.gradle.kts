plugins {
	`kotlin-dsl`
}

// These are the Gradle plugins our convention plugins (in src/main/kotlin)
// apply by id. They must be on build-logic's classpath to be usable there.
dependencies {
	implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
	implementation("org.jetbrains.kotlin:kotlin-allopen:2.3.21") // kotlin("plugin.spring")
	implementation("org.jetbrains.kotlin:kotlin-noarg:2.3.21") // kotlin("plugin.jpa")
	implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.0")
	implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
