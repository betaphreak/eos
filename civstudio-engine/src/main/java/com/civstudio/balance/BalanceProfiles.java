package com.civstudio.balance;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import com.civstudio.data.WorldSource;
import com.civstudio.data.WorldSources;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authored <b>balance profiles</b>, read from content rather than compiled in — the delivery of
 * {@code docs/studio-control-plane-plan.md} §A2/A3, mirroring {@link com.civstudio.era.EconomyCatalog}.
 * A profile is a named {@link BalanceProfile} (agent-behaviour tuning); the resource is a map
 * {@code profileKey → BalanceProfile}, with {@code "default"} always resolvable:
 * <pre>{@code
 * { "default": { "firm": {…}, "bank": {…}, … }, "aggressive-tax": { … } }
 * }</pre>
 *
 * <h2>Failure contract — as {@code EconomyCatalog}, load-bearing</h2>
 * A profile sets how every agent behaves, so a silently-wrong one is a run that quietly used other
 * numbers than it reported:
 * <ul>
 * <li><b>absent</b> resource → only {@code "default"} exists, equal to {@link BalanceProfile#DEFAULT}.
 *     Behaviour-neutral, the offline/pre-cutover path.</li>
 * <li><b>present but malformed</b> → {@link IllegalStateException} at load, never a silent fallback to
 *     the compiled defaults.</li>
 * </ul>
 *
 * <p>Serialisation is plain Jackson over the annotation-free config records (A2). {@link
 * #canonicalJson()} emits the compiled {@code "default"} profile — the seed content and the
 * round-trip fixture.
 */
public final class BalanceProfiles {

	/** The content path; served by whichever {@link WorldSource} is installed. */
	public static final String RESOURCE = "/balance/profiles.json";

	/** The always-present key; resolves to {@link BalanceProfile#DEFAULT} unless content overrides it. */
	public static final String DEFAULT_KEY = "default";

	static final ObjectMapper MAPPER = JsonMapper.builder()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
			.build();

	private static final BalanceProfiles INSTANCE = load(WorldSources.current());

	private final Map<String, BalanceProfile> byKey;

	private BalanceProfiles(Map<String, BalanceProfile> byKey) {
		this.byKey = byKey;
	}

	/** The shared, content-loaded profiles. */
	public static BalanceProfiles get() {
		return INSTANCE;
	}

	/**
	 * The profile for a key, or {@link BalanceProfile#DEFAULT} when the key is unauthored (including
	 * the always-safe {@link #DEFAULT_KEY}). Never {@code null} — an unknown key founds on the
	 * defaults rather than failing, the same forgiving shape a race with no economy column takes.
	 *
	 * @param key the profile key; {@code null} is read as {@link #DEFAULT_KEY}
	 * @return the authored profile, or {@link BalanceProfile#DEFAULT}
	 */
	public BalanceProfile get(String key) {
		return byKey.getOrDefault(key == null ? DEFAULT_KEY : key, BalanceProfile.DEFAULT);
	}

	/** The authored keys, in load order ({@code "default"} always present). */
	public java.util.Set<String> keys() {
		return java.util.Collections.unmodifiableSet(byKey.keySet());
	}

	/** Read the profiles from a source. Package-private and parameterised so a test can drive it. */
	static BalanceProfiles load(WorldSource source) {
		Map<String, BalanceProfile> byKey = new LinkedHashMap<>();
		// "default" is always resolvable, even with no resource; content may override it
		byKey.put(DEFAULT_KEY, BalanceProfile.DEFAULT);
		try (InputStream in = source.open(RESOURCE)) {
			if (in == null)
				return new BalanceProfiles(byKey); // unauthored: default-only
			Map<String, BalanceProfile> raw =
					MAPPER.readValue(in, new TypeReference<Map<String, BalanceProfile>>() {
					});
			byKey.putAll(raw);
		} catch (IOException | RuntimeException e) {
			// LOUD, not lenient — a profile is load-bearing (see the class javadoc)
			throw new IllegalStateException(
					"BalanceProfiles: " + RESOURCE + " is present but unreadable — refusing to fall"
							+ " back to the compiled defaults, since that would silently change how"
							+ " every agent behaves: " + e.getMessage(), e);
		}
		return new BalanceProfiles(byKey);
	}

	/**
	 * The canonical JSON for the compiled defaults — a one-entry map {@code {"default": DEFAULT}},
	 * how the seed content is produced and what the round-trip test checks.
	 *
	 * @return the map as pretty-printed JSON
	 */
	public static String canonicalJson() {
		Map<String, BalanceProfile> out = new LinkedHashMap<>();
		out.put(DEFAULT_KEY, BalanceProfile.DEFAULT);
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
	}
}
