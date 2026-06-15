plugins {
	id("floating-lyric.spring-service")
}

dependencies {
	// Shared library dependency — auth compiles against the contracts module.
	// This is how a service depends on shared CODE. (Services talk to each
	// other over the network, not via project() dependencies.)
	implementation(project(":libs:contracts"))
}
