package com.civstudio.geo;

import java.util.Map;

/**
 * The <b>art binding</b> for a base {@link Terrain} — how it is drawn on the map,
 * as opposed to {@code Terrain}'s gameplay yields. Exported (curated to the same
 * settleable-land subset as {@link Terrain}) from {@code
 * data/civ4/CIV4ArtDefines_Terrain.xml} by {@link
 * com.civstudio.geo.export.TerrainArtExporter} into {@code /map/terrain-art.json},
 * keyed by the gameplay {@link #terrain() TERRAIN_*} type (the art's own {@code
 * ART_DEF_TERRAIN_*} tag is joined to it at export time via {@code
 * TerrainInfo.ArtDefineTag}), so the resource aligns 1:1 with {@code terrains.json}
 * and needs no runtime join. See {@code docs/ported-terrain-art-system.md} §4.1, §10.
 * <p>
 * The {@code path}/{@code grid}/{@code detail} are {@code Art/...}-relative DDS
 * texture paths under the {@code UnpackedArt/art} source tree (offline, Git-LFS);
 * the web build decodes and bakes them into web-optimised imagery — the {@code .dds}
 * never ships to the browser.
 *
 * @param terrain      the gameplay terrain key this art draws ({@code TERRAIN_*})
 * @param artTag       the Civ4 art define tag ({@code ART_DEF_TERRAIN_*})
 * @param path         main blended texture ({@code Art/Terrain/Textures/.../*Blend.dds})
 * @param grid         grid-overlay texture path
 * @param detail       close-up detail texture path
 * @param layerOrder   paint/blend priority between terrains ({@code <LayerOrder>})
 * @param alphaShader  whether the terrain uses the alpha-blend shader
 * @param blend        the 16-way auto-tiling table: neighbour-bitmask {@code "01".."15"}
 *                     &rarr; {@code "index,rotation ..."} transition-tile entries
 *                     ({@code <TextureBlendNN>}); the fully-surrounded {@code "15"} lists
 *                     many variants (see {@code docs/ported-terrain-art-system.md} §4.1)
 */
public record TerrainArtInfo(
		String terrain,
		String artTag,
		String path,
		String grid,
		String detail,
		int layerOrder,
		boolean alphaShader,
		Map<String, String> blend) {
}
