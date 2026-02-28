package de.hytalede.safezones.hytale.worldmap;

import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import de.hytalede.safezones.core.CellType;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.ClaimType;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneRect;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Map;
import java.util.Objects;

/**
 * Adds per-player (staff-only) SafeZone markers when /safezone show is enabled.
 *
 * <p>WorldMap chunk overlays are world-global; markers are the only per-player hook we have.</p>
 */
public final class SafeZonesShowMarkerProvider implements WorldMapManager.MarkerProvider {
	public static final String KEY = "safezones_show";
	private static final int CELL_SIZE = 16;
	private static final int ZONE_SCAN_RADIUS_CELLS = 24;
	private static final int MAX_HOUSE_MARKERS_PER_UPDATE = 200;
	// Reuse an existing marker image shipped with the server assets (used by built-in tasks).
	private static final String MARKER_IMAGE = "Home.png";
	// Keep corner markers label-less to avoid clutter (label marker below carries the text).
	private static final String BORDER_MARKER_NAME = "";
	private static final String HOUSE_MARKER_IMAGE = "Home.png";

	private final SafeZonesHytalePlugin plugin;

	public SafeZonesShowMarkerProvider(SafeZonesHytalePlugin plugin) {
		this.plugin = Objects.requireNonNull(plugin, "plugin");
	}

	@Override
	public void update(World world, Player player, MarkersCollector collector) {
		if (player == null || collector == null) {
			return;
		}
		var uuid = player.getUuid();
		if (!plugin.isShowEnabledFor(uuid) || !plugin.canUseShow(player)) {
			return;
		}
		int playerCellX = resolvePlayerChunkCoordinate(player, "getChunkPositionX") * 2;
		int playerCellZ = resolvePlayerChunkCoordinate(player, "getChunkPositionZ") * 2;
		updateInternal(player, playerCellX, playerCellZ, collector::add);
	}

	private void updateInternal(Player player, int playerCellX, int playerCellZ, MarkerSink sink) {
		var uuid = player.getUuid();
		if (!plugin.isShowEnabledFor(uuid) || !plugin.canUseShow(player)) {
			return;
		}
		SafeZonesHytalePlugin.GridMode gridMode = plugin.getShowGridMode(uuid);
		boolean showHouse = plugin.isShowHouseEnabledFor(uuid);
		if (!gridMode.isEnabled() && !showHouse) {
			return;
		}

		SafeZonesSnapshot snapshot = plugin.getSnapshot();
		if (snapshot.zones().isEmpty()) {
			return;
		}

		if (gridMode.isEnabled()) {
			for (SafeZone zone : snapshot.zones()) {
				ZoneRect rect = zone.rect();
				if (!isNear(playerCellX, playerCellZ, rect, ZONE_SCAN_RADIUS_CELLS)) {
					continue;
				}
				// Corner markers are very spammy (4 per zone) and can cause the map to appear "empty"
				// if the marker update packet becomes too large. In-game borders already show the outline,
				// so on the map we keep only a single label marker per zone.
				addLabelMarker(sink, zone.id(), rect);
			}
		}

		// Add per-lot markers (claimed HOUSE cells) near the player.
		if (showHouse) {
			addClaimedHouseLotMarkers(snapshot, sink, playerCellX, playerCellZ);
		}
	}

	private static void addCornerMarkers(MarkerSink sink, String zoneId, ZoneRect rect) {
		int minX = rect.minX() * CELL_SIZE;
		int maxX = (rect.maxX() + 1) * CELL_SIZE;
		int minZ = rect.minZ() * CELL_SIZE;
		int maxZ = (rect.maxZ() + 1) * CELL_SIZE;

		sendMarker(sink, zoneId, "NW", minX, minZ);
		sendMarker(sink, zoneId, "NE", maxX, minZ);
		sendMarker(sink, zoneId, "SW", minX, maxZ);
		sendMarker(sink, zoneId, "SE", maxX, maxZ);
	}

	private static void sendMarker(MarkerSink sink, String zoneId, String corner, int x, int z) {
		String id = "SafeZoneBorder_" + zoneId + "_" + corner;
		String name = BORDER_MARKER_NAME;
		MapMarker marker = createMapMarker(id, name, MARKER_IMAGE, new Transform(new Position(x + 0.5, 0.0, z + 0.5), new Direction(0.0f, 0.0f, 0.0f)));
		sink.add(marker);
	}

	/**
	 * Adds a center marker whose {@link MapMarker#name} is rendered as UI text by the client.
	 *
	 * <p>This is the only non-pixel text mechanism available on the world map (used by Spawn/Home/Death markers).</p>
	 */
	private static void addLabelMarker(MarkerSink sink, String zoneId, ZoneRect rect) {
		int minX = rect.minX() * CELL_SIZE;
		int maxX = (rect.maxX() + 1) * CELL_SIZE;
		int minZ = rect.minZ() * CELL_SIZE;
		int maxZ = (rect.maxZ() + 1) * CELL_SIZE;

		double centerX = (minX + maxX) / 2.0;
		double centerZ = (minZ + maxZ) / 2.0;

		String id = "SafeZoneLabel_" + zoneId;
		String name = zoneId;
		MapMarker marker = createMapMarker(id, name, MARKER_IMAGE, new Transform(new Position(centerX, 0.0, centerZ), new Direction(0.0f, 0.0f, 0.0f)));
		sink.add(marker);
	}

	/**
	 * Adds a small house icon for claimed lots, with hover text showing the owner.
	 *
	 * <p>MapMarker has no per-marker scale. The only way to appear "smaller" is by choosing a subtler icon.</p>
	 */
	private static void addClaimedHouseLotMarkers(
			SafeZonesSnapshot snapshot,
			MarkerSink sink,
			int playerCellX,
			int playerCellZ
	) {
		int minX = playerCellX - ZONE_SCAN_RADIUS_CELLS;
		int maxX = playerCellX + ZONE_SCAN_RADIUS_CELLS;
		int minZ = playerCellZ - ZONE_SCAN_RADIUS_CELLS;
		int maxZ = playerCellZ + ZONE_SCAN_RADIUS_CELLS;

		int sent = 0;
		for (Map.Entry<ChunkPos, ChunkOverride> e : snapshot.chunkOverrides().entrySet()) {
			ChunkPos cell = e.getKey();
			if (cell.x() < minX || cell.x() > maxX || cell.z() < minZ || cell.z() > maxZ) {
				continue;
			}
			ChunkOverride ov = e.getValue();
			if (ov == null || ov.type() != CellType.HOUSE) {
				continue;
			}
			if (ov.claimType() != ClaimType.PLAYER_OWNER || ov.owner() == null || ov.owner().isBlank()) {
				continue;
			}

			// Exact center of the 16x16 area in world coordinates:
			// blocks are [min..min+15], block-centers average to min+8.0.
			double centerBlockX = cell.x() * (double) CELL_SIZE + 8.0;
			double centerBlockZ = cell.z() * (double) CELL_SIZE + 8.0;

			String id = "SafeZoneHouse_" + cell.toKey();
			String name = ov.owner(); // hover/label text in map UI
			MapMarker marker = createMapMarker(
					id,
					name,
					HOUSE_MARKER_IMAGE,
					new Transform(new Position(centerBlockX, 0.0, centerBlockZ), new Direction(0.0f, 0.0f, 0.0f))
			);
			sink.add(marker);
			if (++sent >= MAX_HOUSE_MARKERS_PER_UPDATE) {
				break;
			}
		}
	}

	private static MapMarker createMapMarker(String id, String customName, String markerImage, Transform transform) {
		MapMarker marker = new MapMarker();
		marker.id = id;
		marker.name = null; // client uses customName for plain text labels
		marker.customName = customName;
		marker.markerImage = markerImage;
		marker.transform = transform;
		marker.contextMenuItems = null;
		marker.components = null;
		return marker;
	}

	private static int resolvePlayerChunkCoordinate(Player player, String methodName) {
		try {
			Object value = player.getClass().getMethod(methodName).invoke(player);
			if (value instanceof Number n) {
				return n.intValue();
			}
		} catch (Throwable ignored) {
		}
		return 0;
	}

	@FunctionalInterface
	private interface MarkerSink {
		void add(MapMarker marker);
	}

	private static boolean isNear(int cx, int cz, ZoneRect rect, int radiusChunks) {
		int dx = 0;
		if (cx < rect.minX()) dx = rect.minX() - cx;
		else if (cx > rect.maxX()) dx = cx - rect.maxX();
		int dz = 0;
		if (cz < rect.minZ()) dz = rect.minZ() - cz;
		else if (cz > rect.maxZ()) dz = cz - rect.maxZ();
		return Math.max(dx, dz) <= radiusChunks;
	}
}

