package com.civstudio.server.mcp;

import java.io.IOException;
import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import com.civstudio.server.web.ResourceManifest;
import com.civstudio.settlement.ProvincePlotStore;

/**
 * The bill of materials as an MCP tool — the same {@link ResourceManifest} the web viewer reads from
 * {@code /api/resources}, so an LLM inspecting this server can enumerate what world data exists and
 * how big it is without being told out of band, and without guessing endpoint names.
 *
 * <p>Read-only and world-level (no session), so it sits alongside {@link SessionMcpTools} under the
 * same anonymous-spectate stance.
 */
@Component
public class ResourceMcpTools {

	@McpTool(name = "list_resources",
			description = "Bill of materials for this server's world-level generated resources: the "
					+ "endpoint serving each, its gzip size in bytes, whether the web client "
					+ "prefetches it at boot, and which generated artifact it is baked from. Use to "
					+ "discover what map/tech/building data is available before fetching any of it.")
	public Bom listResources() throws IOException {
		return new Bom(ResourceManifest.entries(), ResourceManifest.eagerBytes(),
				ProvincePlotStore.MAP_VERSION);
	}

	/**
	 * @param resources  every servable world-level resource
	 * @param eagerBytes gzip bytes in the boot-time prefetch set
	 * @param mapVersion the world generation these resources describe — a bump means every
	 *                   province's baked plots changed, so anything cached against an older value is
	 *                   stale
	 */
	public record Bom(List<ResourceManifest.Entry> resources, long eagerBytes, int mapVersion) {
	}
}
