package com.civstudio.data;

import java.io.InputStream;

/**
 * The default {@link WorldSource}: reads resources straight off the classpath, exactly as the loaders
 * did before the seam existed ({@code SomeClass.class.getResourceAsStream(path)}). Absolute paths
 * resolve from the classpath root, so this is byte-for-byte behavior-neutral with the old code.
 */
public final class ClasspathWorldSource implements WorldSource {

	@Override
	public InputStream open(String path) {
		return ClasspathWorldSource.class.getResourceAsStream(path);
	}

	@Override
	public boolean exists(String path) {
		return ClasspathWorldSource.class.getResource(path) != null;
	}
}
