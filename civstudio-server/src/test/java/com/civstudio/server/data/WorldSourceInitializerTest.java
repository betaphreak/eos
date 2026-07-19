package com.civstudio.server.data;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.civstudio.data.ClasspathWorldSource;
import com.civstudio.data.WorldSources;

/** The composition-root wiring: mode → the right WorldSource, bad config fails loudly. */
class WorldSourceInitializerTest {

	@AfterEach
	void reset() {
		WorldSources.reset(); // don't leak a source into the shared test JVM
	}

	@Test
	void defaultsToClasspath() {
		WorldSourceInitializer.apply(new MockEnvironment());
		assertInstanceOf(ClasspathWorldSource.class, WorldSources.current());
	}

	@Test
	void explicitClasspath() {
		WorldSourceInitializer.apply(new MockEnvironment().withProperty("civstudio.world-source.mode", "classpath"));
		assertInstanceOf(ClasspathWorldSource.class, WorldSources.current());
	}

	@Test
	void unknownModeFailsFast() {
		MockEnvironment env = new MockEnvironment().withProperty("civstudio.world-source.mode", "bogus");
		assertThrows(IllegalStateException.class, () -> WorldSourceInitializer.apply(env));
	}

	@Test
	void fixtureWithoutPathFailsFast() {
		MockEnvironment env = new MockEnvironment().withProperty("civstudio.world-source.mode", "fixture");
		assertThrows(IllegalStateException.class, () -> WorldSourceInitializer.apply(env));
	}
}
