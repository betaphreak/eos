package com.civstudio.server.data;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.civstudio.data.ClasspathWorldSource;
import com.civstudio.data.WorldSource;
import com.civstudio.data.WorldSources;

/** The composition-root wiring: mode → the right WorldSource, bad config fails loudly. */
class WorldSourceInitializerTest {

	private WorldSource previous;

	@BeforeEach
	void save() {
		previous = WorldSources.current();
	}

	@AfterEach
	void restore() {
		// Restore the suite-wide source (the world-bundle fixture installed by
		// FixtureWorldSourceInstaller), not the classpath default — generated/ is no longer committed,
		// so leaking a ClasspathWorldSource into the shared JVM would break every later test that loads
		// world data.
		WorldSources.set(previous);
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
