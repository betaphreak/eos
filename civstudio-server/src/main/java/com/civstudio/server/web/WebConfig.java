package com.civstudio.server.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.civstudio.server.CivStudioProperties;

/**
 * Declarative CORS for the browser: the static map site (Static Web Apps) calls this server's
 * {@code /api/**} feed and polls {@code /actuator/health} from a different origin. Replaces the
 * hand-rolled CORS handling in the old {@code FeedServer}. Allowed origins come from
 * {@link CivStudioProperties.Cors} (default: the production sites); any {@code localhost} port is
 * additionally allowed for local development.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final CivStudioProperties props;

	public WebConfig(CivStudioProperties props) {
		this.props = props;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		// the JSON feed + SSE stream + control/command POSTs. (The site's /actuator/health poll is
		// CORS-allowed separately via management.endpoints.web.cors in application.yml — Actuator
		// endpoints have their own handler mapping and don't see this CorsRegistry.)
		registry.addMapping("/api/**")
				.allowedOriginPatterns(originPatterns())
				.allowedMethods("GET", "POST", "OPTIONS")
				.allowedHeaders("*")
				.maxAge(600);
	}

	// the configured origins plus any localhost port (allowedOriginPatterns supports the wildcard)
	private String[] originPatterns() {
		List<String> patterns = new ArrayList<>(props.getCors().getOrigins());
		patterns.add("http://localhost:*");
		patterns.add("http://127.0.0.1:*");
		return patterns.toArray(new String[0]);
	}
}
