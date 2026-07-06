package com.civstudio.geo;

/**
 * The <b>art binding</b> for one piece of a route (road / rail / path / …) — a single
 * connection-and-rotation variant of a {@code ROUTE_*} type's on-map model. Exported from
 * {@code data/civ4/CIV4RouteModelInfos.xml} by {@link
 * com.civstudio.geo.export.RouteModelExporter} into {@code /map/route-models.json}.
 * <p>
 * Routes are the granular Civ4 auto-tiling system (see {@code
 * docs/ported-terrain-art-system.md} §4.5, §6.3): for a plot's set of connected
 * neighbours, the renderer picks the {@code RouteModelInfo} of the right {@link
 * #routeType()} whose {@link #connections()} matches, then applies the {@link #rotations()}
 * symmetry so one mesh (e.g. a straight segment) covers every orientation; {@link
 * #lateModelFile()} swaps the look in later eras. This is why the source lists ~70 pieces
 * per route type but far fewer distinct meshes.
 * <p>
 * <b>Not rivers.</b> Civ4 has no {@code ROUTE_RIVER} and binds no river art here — river
 * edge tiles are engine-hardcoded (see {@code docs/river-rendering.md} §4). This record is
 * the roads/rails feature only.
 * <p>
 * {@link #modelFile()}/{@link #lateModelFile()} are {@code Art/...}-relative paths under
 * the {@code UnpackedArt/art} source tree — and they are 3D {@code .nif} meshes, so a 2D
 * web client needs the offline {@code .nif}→sprite bake ({@code
 * ported-terrain-art-system.md} §10/§11) before these can render; the exporter only emits
 * the binding/manifest.
 *
 * @param routeType        the {@code ROUTE_*} type this piece draws
 * @param modelFileKey     short id of the mesh variant (e.g. {@code "A00"})
 * @param modelFile        base-era model path ({@code Art/Terrain/Routes/.../x.nif})
 * @param lateModelFile    modern-era model swap (often equal to {@code modelFile})
 * @param animated         whether the model is animated
 * @param connections      the real neighbour connections this piece covers ({@code "-"} =
 *                         none, else directions like {@code "N"} / {@code "NE SW"})
 * @param modelConnections the connections baked into the mesh (before rotation)
 * @param rotations        the yaw angles (degrees) the one mesh is reused at, e.g.
 *                         {@code [0, 90, 180, 270]}
 */
public record RouteModelInfo(
		String routeType,
		String modelFileKey,
		String modelFile,
		String lateModelFile,
		boolean animated,
		String connections,
		String modelConnections,
		int[] rotations) {
}
