package com.civstudio.data;

import java.util.function.Function;

/**
 * A lazily-built, source-tracking cache for a value derived from the active {@link WorldSource} — the
 * fix for the eager-static-singleton hazard the content catalogs shared.
 *
 * <p><b>The bug it replaces.</b> A catalog written as {@code static final T INSTANCE =
 * load(WorldSources.current())} captures the source <em>at class-load time</em>. That is a latent
 * ordering trap: if anything touches the catalog before the composition root installs the real source
 * (a stray early call to {@code Era.economy(...)} during boot, say), it captures the classpath default
 * forever, and every later read silently uses the wrong world — with no error. The old {@code
 * UnitCatalog} carried exactly this caveat.
 *
 * <p><b>The fix.</b> Build on first use, and rebuild if the active source has since changed — keyed on
 * source <em>identity</em> ({@code WorldSources.set} installs a new object). So the value always
 * reflects the source that is active when it is actually read, regardless of class-load order, and a
 * rebuild happens only when the source genuinely changes (once, at boot). The hot path is two volatile
 * reads; the rebuild is synchronized and double-checked.
 *
 * @param <T> the cached value type
 */
public final class WorldSourceCache<T> {

	private final Function<WorldSource, T> loader;
	private volatile T value;
	private volatile WorldSource builtFrom;

	/**
	 * @param loader builds the value from a source; called on first {@link #get()} and again whenever
	 *               the active source changes. Must be side-effect-free enough to run more than once.
	 */
	public WorldSourceCache(Function<WorldSource, T> loader) {
		this.loader = loader;
	}

	/** The value for the currently-active {@link WorldSource}, rebuilding if the source changed. */
	public T get() {
		WorldSource cur = WorldSources.current();
		T v = value;
		if (v != null && cur == builtFrom)
			return v;
		return rebuild(cur);
	}

	private synchronized T rebuild(WorldSource cur) {
		if (value != null && cur == builtFrom) // another thread rebuilt while we waited
			return value;
		T v = loader.apply(cur);
		value = v;
		builtFrom = cur;
		return v;
	}
}
