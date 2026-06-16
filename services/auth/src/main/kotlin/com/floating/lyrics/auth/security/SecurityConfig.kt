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
