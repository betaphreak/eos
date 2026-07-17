package com.civstudio.server.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.civstudio.server.CivStudioProperties;

/**
 * Declarative CORS for the browser: the static map site (Static Web Apps) calls this server's
 * {@code /api/**} feed from a different origin, and — since login (phase 2) rides an httpOnly
 * session cookie scoped to the shared parent domain — those requests are <b>credentialed</b>. The
 * config therefore allows credentials and echoes a specific allowed origin (never {@code *}, which
 * is illegal with credentials). Allowed origins come from {@link CivStudioProperties.Cors}
 * (default: the production sites); any {@code localhost} port is additionally allowed for local
 * development.
 * <p>
 * Exposed as a {@link CorsConfigurationSource} bean so Spring Security's CORS filter uses the same
 * rules (see {@link com.civstudio.server.auth.SecurityConfig}); the Actuator health poll is
 * CORS-allowed separately via {@code management.endpoints.web.cors} in {@code application.yml}.
 */
@Configuration
public class WebConfig {

	private final CivStudioProperties props;

	public WebConfig(CivStudioProperties props) {
		this.props = props;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration cfg = new CorsConfiguration();
		cfg.setAllowedOriginPatterns(originPatterns());
		// DELETE is required by the lobby's "delete run" (SessionController#delete); without it the
		// cross-origin (anbennar → dev) preflight blocks the request and the delete silently no-ops.
		cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cfg.setAllowedHeaders(List.of("*"));
		cfg.setAllowCredentials(true); // the session cookie must ride cross-subdomain
		cfg.setMaxAge(600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cfg);
		return source;
	}

	// the configured origins plus any localhost port (allowedOriginPatterns supports the wildcard)
	private List<String> originPatterns() {
		List<String> patterns = new ArrayList<>(props.getCors().getOrigins());
		patterns.add("http://localhost:*");
		patterns.add("http://127.0.0.1:*");
		return patterns;
	}
}
