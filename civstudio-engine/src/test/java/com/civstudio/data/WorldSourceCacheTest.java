package com.civstudio.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * {@link WorldSourceCache} — the fix for the eager-static-singleton capture the content catalogs
 * shared: build on first use, and rebuild when the active {@link WorldSource} changes, so the value
 * always reflects the source active when it is read rather than the one present at class-load.
 */
class WorldSourceCacheTest {

	private static WorldSource emptySource() {
		return new WorldSource() {
			@Override
			public InputStream open(String path) {
				return null;
			}

			@Override
			public boolean exists(String path) {
				return false;
			}
		};
	}

	@Test
	void buildsLazilyAndRebuildsWhenTheSourceChanges() {
		WorldSource previous = WorldSources.current();
		try {
			AtomicInteger builds = new AtomicInteger();
			// the loader also asserts it is handed the CURRENTLY-active source, not a stale capture
			WorldSource[] seen = new WorldSource[1];
			WorldSourceCache<Integer> cache = new WorldSourceCache<>(src -> {
				seen[0] = src;
				return builds.incrementAndGet();
			});

			WorldSource a = emptySource();
			WorldSources.set(a);
			assertEquals(1, cache.get(), "first get builds");
			assertSame(a, seen[0], "the loader is handed the active source");
			assertEquals(1, cache.get(), "same source → no rebuild");
			assertEquals(1, builds.get(), "the loader ran exactly once for one source");

			WorldSource b = emptySource();
			WorldSources.set(b);
			assertEquals(2, cache.get(), "a changed source rebuilds");
			assertSame(b, seen[0], "the rebuild used the new source");

			WorldSources.set(a);
			assertEquals(3, cache.get(), "switching back rebuilds too — identity, not equality");
		} finally {
			WorldSources.set(previous); // never leak a test source into the shared suite
		}
	}
}
