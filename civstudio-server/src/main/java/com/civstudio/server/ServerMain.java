package com.civstudio.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The Spring Boot entry point for the CivStudio spectator/interactive server (see
 * {@code docs/client-server.md} and {@code docs/spring-boot-migration.md}). Boot stands up the
 * embedded web server (Spring MVC on virtual threads), the {@link SessionHost} bean, the REST
 * controllers, and Actuator health/metrics; {@link DemoSessionSeeder} founds the six-caravan
 * demo session once the context is ready.
 *
 * <pre>
 *   mvn -pl civstudio-server -am spring-boot:run     # or: java -jar civstudio-server-*.jar
 *   # then open http://localhost:8080/
 * </pre>
 *
 * The port follows {@code server.port} (defaulting to {@code $PORT} then 8080 — see
 * {@code application.yml}), the container-ingress convention.
 */
@SpringBootApplication
@EnableConfigurationProperties(CivStudioProperties.class)
public class ServerMain {

	public static void main(String[] args) {
		SpringApplication.run(ServerMain.class, args);
	}
}
