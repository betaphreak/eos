package com.civstudio.server.web;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.civstudio.server.CivStudioProperties;
import com.civstudio.server.SessionHost;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Admin-only server operations behind {@code /api/admin/**} — the backend for the admin console
 * ({@code web/admin.html}, served at {@code /}). Every endpoint is gated by {@link
 * CurrentUserResolver#isAdmin} (the existing {@code ROLE_ADMIN} / {@code civstudio.auth.admins}
 * allow-list); a non-admin gets {@code 403}. The console reads session state from the ordinary
 * {@code /api/sessions} endpoints (admins bypass the ownership check there). See {@code
 * docs/admin-console.md}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	private final PlotService plots;
	private final CurrentUserResolver currentUser;
	private final CivStudioProperties props;
	private final SessionHost host;

	// one background warm at a time; progress is read from PlotService.status() (cached count grows)
	private final AtomicBoolean warming = new AtomicBoolean(false);

	public AdminController(PlotService plots, CurrentUserResolver currentUser,
			CivStudioProperties props, SessionHost host) {
		this.plots = plots;
		this.currentUser = currentUser;
		this.props = props;
		this.host = host;
	}

	/** A consolidated admin readout: plot-cache status, warm progress, server + identity. */
	@GetMapping("/status")
	public Map<String, Object> status(HttpServletRequest http) {
		requireAdmin(http);
		PlotService.PlotStatus ps = plots.status();
		Map<String, Object> plot = new LinkedHashMap<>();
		plot.put("cached", ps.cached());
		plot.put("total", ps.total());
		plot.put("generating", ps.generating());
		plot.put("warming", warming.get());

		Runtime rt = Runtime.getRuntime();
		Map<String, Object> server = new LinkedHashMap<>();
		server.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
		server.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
		server.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
		server.put("sessions", host.list().size());
		server.put("admins", props.getAuth().getAdmins().size());
		server.put("you", currentUser.userId(http));

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("plots", plot);
		out.put("server", server);
		return out;
	}

	/** Drop the whole plot cache (LRU + volume); provinces regenerate fresh on next request. */
	@PostMapping("/plots/clear")
	public Map<String, Object> clearPlots(HttpServletRequest http) {
		requireAdmin(http);
		return Map.of("cleared", plots.clear());
	}

	/**
	 * Warm the world: generate every province's grid into the cache in the background, so visitors
	 * never hit a cold, sim-pausing generation (each grid is generated under the sim pause). A no-op
	 * if a warm is already running; poll {@code /api/admin/status} for progress.
	 */
	@PostMapping("/plots/warm")
	public Map<String, Object> warm(HttpServletRequest http) {
		requireAdmin(http);
		if (!warming.compareAndSet(false, true))
			return Map.of("started", false, "reason", "already warming");
		Thread.ofVirtual().name("plot-warm").start(() -> {
			try {
				plots.warmAll(); // generates + caches every province on a miss, under the sim pause
			} finally {
				warming.set(false);
			}
		});
		return Map.of("started", true);
	}

	/** Drop the Anbennar mod fetch cache (the map rasters / history the engine fetches from GitLab),
	 * forcing a re-fetch on next use. The Civ4 art cache is build-time only (the running server never
	 * reads it), so there is nothing server-side to drop for it. */
	@PostMapping("/caches/anbennar/clear")
	public Map<String, Object> clearAnbennarCache(HttpServletRequest http) {
		requireAdmin(http);
		return Map.of("deleted", deleteTree(Path.of(props.getAnbennar().getCacheDir())));
	}

	private void requireAdmin(HttpServletRequest http) {
		if (!currentUser.isAdmin(http))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
	}

	// recursively delete a directory's contents (best-effort), returning the file count removed
	private static int deleteTree(Path dir) {
		if (!Files.isDirectory(dir))
			return 0;
		int[] n = { 0 };
		try (var s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> {
				if (p.equals(dir))
					return; // keep the cache root itself
				try {
					if (Files.deleteIfExists(p) && !Files.isDirectory(p))
						n[0]++;
				} catch (IOException ignored) {
					// best-effort
				}
			});
		} catch (IOException ignored) {
			// nothing to delete
		}
		return n[0];
	}
}
