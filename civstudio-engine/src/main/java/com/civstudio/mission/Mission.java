package com.civstudio.mission;

import java.util.List;

/**
 * One node of an Anbennar mission tree, imported from {@code missions/*.txt} by
 * {@link com.civstudio.mission.export.MissionExporter}. A mission is a goal a country works toward:
 * its {@code trigger} is the condition that completes it, its {@code effect} the reward. See
 * {@code docs/campaign-selector.md}.
 * <p>
 * {@code title}/{@code description} are the localised strings (from {@code
 * localisation/*_missions_l_english.yml}, keyed {@code <key>_title}/{@code _desc}); {@code null} when
 * the mod ships no loc for the key. {@code trigger}/{@code effect} are captured as their raw
 * (whitespace-collapsed) Clausewitz bodies — the mission DSL is not itself parsed; this preserves the
 * content for a later "mission trees as content" import without pretending to model EU4's script.
 *
 * @param key               the mission id (e.g. {@code arakeprun_ruins_of_greatness})
 * @param title             localised title, or {@code null}
 * @param description       localised description, or {@code null}
 * @param icon              the mission icon sprite key (e.g. {@code mission_city_of_victory_vij})
 * @param position          the node's position in the tree, or {@code null}
 * @param requiredMissions  the keys of the missions that must precede this one
 * @param highlightProvinces the {@code province_id}s the mission draws attention to
 * @param trigger           the raw completion condition body, or {@code null}
 * @param effect            the raw reward body, or {@code null}
 */
public record Mission(String key, String title, String description, String icon, Integer position,
		List<String> requiredMissions, List<Integer> highlightProvinces, String trigger, String effect) {
}
