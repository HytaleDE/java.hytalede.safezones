package de.hytalede.safezones.hytale.worldmap;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.map.WorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapSettings;
import de.hytalede.safezones.core.CellType;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.ClaimType;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps an existing world map generator and draws SafeZones borders on top.
 *
 * <p>This avoids re-rendering terrain pixels (height/tint/fluid/shading) and only touches a tiny amount
 * of pixels per map chunk (thin border lines).</p>
 */
public final class SafeZonesBorderOverlayWorldMap implements IWorldMap {
	private static final int CELL_SIZE = 16;
	private static final int BORDER_SIZE = 2; // pixels
	private static final int BORDER_ALPHA_DEFAULT = 180;
	private static final int BORDER_ALPHA_UNUSED = 200;

	private final IWorldMap base;

	public SafeZonesBorderOverlayWorldMap(IWorldMap base) {
		this.base = Objects.requireNonNull(base, "base");
	}

	@Override
	public WorldMapSettings getWorldMapSettings() {
		return base.getWorldMapSettings();
	}

	@Override
	public CompletableFuture<WorldMap> generate(World world, int imageWidth, int imageHeight, LongSet chunksToGenerate) {
		return base.generate(world, imageWidth, imageHeight, chunksToGenerate).thenApply(worldMap -> {
			if (worldMap == null) {
				return null;
			}
			SafeZonesSnapshot snapshot = SafeZonesWorldMapState.snapshotOrEmpty();
			for (Long2ObjectMap.Entry<MapImage> e : worldMap.getChunks().long2ObjectEntrySet()) {
				long index = e.getLongKey();
				MapImage image = e.getValue();
				if (image == null || image.data == null || image.width <= 0 || image.height <= 0) {
					continue;
				}
				applyBorders(snapshot, index, image);
			}
			return worldMap;
		});
	}

	@Override
	public CompletableFuture<Map<String, MapMarker>> generatePointsOfInterest(World world) {
		try {
			CompletableFuture<Map<String, MapMarker>> future = base.generatePointsOfInterest(world);
			if (future == null) {
				return CompletableFuture.completedFuture(Collections.emptyMap());
			}
			return future.exceptionally(ex -> Collections.emptyMap());
		} catch (Throwable ignored) {
			return CompletableFuture.completedFuture(Collections.emptyMap());
		}
	}

	private static void applyBorders(SafeZonesSnapshot snapshot, long mapChunkIndex, MapImage image) {
		// Hard gate: if /safezone show is OFF globally, draw nothing.
		if (!SafeZonesWorldMapState.isShowEnabled()) {
			return;
		}
		int chunkX = ChunkUtil.xOfChunkIndex(mapChunkIndex);
		int chunkZ = ChunkUtil.zOfChunkIndex(mapChunkIndex);

		// SafeZones use 16x16 cells; one 32x32 map chunk contains 2x2 cells.
		final int baseCellX = chunkX * 2;
		final int baseCellZ = chunkZ * 2;

		// Cells within this map chunk:
		Overlay cell00 = overlayForCell(snapshot, new ChunkPos(baseCellX, baseCellZ));         // NW
		Overlay cell10 = overlayForCell(snapshot, new ChunkPos(baseCellX + 1, baseCellZ));     // NE
		Overlay cell01 = overlayForCell(snapshot, new ChunkPos(baseCellX, baseCellZ + 1));     // SW
		Overlay cell11 = overlayForCell(snapshot, new ChunkPos(baseCellX + 1, baseCellZ + 1)); // SE

		// Neighbor cells (needed for borders on the outer chunk edges)
		Overlay west0 = overlayForCell(snapshot, new ChunkPos(baseCellX - 1, baseCellZ));
		Overlay west1 = overlayForCell(snapshot, new ChunkPos(baseCellX - 1, baseCellZ + 1));
		Overlay east0 = overlayForCell(snapshot, new ChunkPos(baseCellX + 2, baseCellZ));
		Overlay east1 = overlayForCell(snapshot, new ChunkPos(baseCellX + 2, baseCellZ + 1));
		Overlay north0 = overlayForCell(snapshot, new ChunkPos(baseCellX, baseCellZ - 1));
		Overlay north1 = overlayForCell(snapshot, new ChunkPos(baseCellX + 1, baseCellZ - 1));
		Overlay south0 = overlayForCell(snapshot, new ChunkPos(baseCellX, baseCellZ + 2));
		Overlay south1 = overlayForCell(snapshot, new ChunkPos(baseCellX + 1, baseCellZ + 2));

		final int w = image.width;
		final int h = image.height;
		final int splitX = w / 2;
		final int splitZ = h / 2;

		// Outer edges (left/right): draw per-half if the overlay changes against neighbor.
		drawVerticalEdge(image, 0, 0, splitZ - 1, cell00, west0);
		drawVerticalEdge(image, 0, splitZ, h - 1, cell01, west1);
		drawVerticalEdge(image, w - 1, 0, splitZ - 1, cell10, east0);
		drawVerticalEdge(image, w - 1, splitZ, h - 1, cell11, east1);

		// Outer edges (top/bottom): draw per-half if the overlay changes against neighbor.
		drawHorizontalEdge(image, 0, splitX - 1, 0, cell00, north0);
		drawHorizontalEdge(image, splitX, w - 1, 0, cell10, north1);
		drawHorizontalEdge(image, 0, splitX - 1, h - 1, cell01, south0);
		drawHorizontalEdge(image, splitX, w - 1, h - 1, cell11, south1);

		// Internal split (vertical): draw a 2px divider (left pixel = west cell color, right pixel = east cell color).
		if (!sameOverlay(cell00, cell10)) {
			drawVerticalDivider(image, splitX, 0, splitZ - 1, cell00, cell10);
		}
		if (!sameOverlay(cell01, cell11)) {
			drawVerticalDivider(image, splitX, splitZ, h - 1, cell01, cell11);
		}

		// Internal split (horizontal): draw a 2px divider (top pixel = north cell color, bottom pixel = south cell color).
		if (!sameOverlay(cell00, cell01)) {
			drawHorizontalDivider(image, 0, splitX - 1, splitZ, cell00, cell01);
		}
		if (!sameOverlay(cell10, cell11)) {
			drawHorizontalDivider(image, splitX, w - 1, splitZ, cell10, cell11);
		}
	}

	private static void drawVerticalEdge(MapImage image, int xEdge, int z0, int z1, Overlay self, Overlay neighbor) {
		if (self.type == OverlayType.NONE) {
			return;
		}
		if (sameOverlay(self, neighbor)) {
			return;
		}
		int xStart = Math.max(0, xEdge - BORDER_SIZE + 1);
		int xEnd = Math.min(image.width - 1, xEdge);
		int alpha = self.type == OverlayType.UNUSED ? BORDER_ALPHA_UNUSED : BORDER_ALPHA_DEFAULT;
		for (int x = xStart; x <= xEnd; x++) {
			for (int z = z0; z <= z1; z++) {
				blendPixel(image, x, z, self.colorRgb, alpha);
			}
		}
	}

	private static void drawHorizontalEdge(MapImage image, int x0, int x1, int zEdge, Overlay self, Overlay neighbor) {
		if (self.type == OverlayType.NONE) {
			return;
		}
		if (sameOverlay(self, neighbor)) {
			return;
		}
		int zStart = Math.max(0, zEdge - BORDER_SIZE + 1);
		int zEnd = Math.min(image.height - 1, zEdge);
		int alpha = self.type == OverlayType.UNUSED ? BORDER_ALPHA_UNUSED : BORDER_ALPHA_DEFAULT;
		for (int z = zStart; z <= zEnd; z++) {
			for (int x = x0; x <= x1; x++) {
				blendPixel(image, x, z, self.colorRgb, alpha);
			}
		}
	}

	private static void drawVerticalDivider(MapImage image, int splitX, int z0, int z1, Overlay west, Overlay east) {
		int xLeft = Math.max(0, splitX - 1);
		int xRight = Math.min(image.width - 1, splitX);
		for (int z = z0; z <= z1; z++) {
			if (west.type != OverlayType.NONE) {
				int alpha = west.type == OverlayType.UNUSED ? BORDER_ALPHA_UNUSED : BORDER_ALPHA_DEFAULT;
				blendPixel(image, xLeft, z, west.colorRgb, alpha);
			}
			if (east.type != OverlayType.NONE) {
				int alpha = east.type == OverlayType.UNUSED ? BORDER_ALPHA_UNUSED : BORDER_ALPHA_DEFAULT;
				blendPixel(image, xRight, z, east.colorRgb, alpha);
			}
		}
	}

	private static void drawHorizontalDivider(MapImage image, int x0, int x1, int splitZ, Overlay north, Overlay south) {
		int zTop = Math.max(0, splitZ - 1);
		int zBottom = Math.min(image.height - 1, splitZ);
		for (int x = x0; x <= x1; x++) {
			if (north.type != OverlayType.NONE) {
				int alpha = north.type == OverlayType.UNUSED ? BORDER_ALPHA_UNUSED : BORDER_ALPHA_DEFAULT;
				blendPixel(image, x, zTop, north.colorRgb, alpha);
			}
			if (south.type != OverlayType.NONE) {
				int alpha = south.type == OverlayType.UNUSED ? BORDER_ALPHA_UNUSED : BORDER_ALPHA_DEFAULT;
				blendPixel(image, x, zBottom, south.colorRgb, alpha);
			}
		}
	}

	private static void blendPixel(MapImage image, int x, int z, int rgb, int alpha) {
		if (x < 0 || z < 0 || x >= image.width || z >= image.height) {
			return;
		}
		int idx = z * image.width + x;
		int base = image.data[idx];
		int br = base >> 24 & 255;
		int bg = base >> 16 & 255;
		int bb = base >> 8 & 255;

		int or = rgb >> 16 & 255;
		int og = rgb >> 8 & 255;
		int ob = rgb & 255;

		int nr = (br * (255 - alpha) + or * alpha) / 255;
		int ng = (bg * (255 - alpha) + og * alpha) / 255;
		int nb = (bb * (255 - alpha) + ob * alpha) / 255;

		image.data[idx] = (nr & 255) << 24 | (ng & 255) << 16 | (nb & 255) << 8 | 255;
	}

	private enum OverlayType { NONE, UNUSED, FREE, HOUSE, STREET, WALL, GATE, SPAWN, MARKET }

	private static final class Overlay {
		final OverlayType type;
		final String groupKey;
		final int colorRgb;

		private Overlay(OverlayType type, String groupKey, int colorRgb) {
			this.type = type;
			this.groupKey = groupKey;
			this.colorRgb = colorRgb;
		}

		static Overlay none() {
			return new Overlay(OverlayType.NONE, "", 0);
		}
	}

	private static boolean sameOverlay(Overlay a, Overlay b) {
		return a.type == b.type && Objects.equals(a.groupKey, b.groupKey);
	}

	private static Overlay overlayForCell(SafeZonesSnapshot snapshot, ChunkPos pos) {
		ChunkOverride ov = snapshot.chunkOverrides().get(pos);
		if (ov == null || ov.type() == null || ov.type() == CellType.NONE) {
			// No plot-type definition:
			// If we're inside any SafeZone rectangle, show an "unused" border around the zone.
			String zoneId = snapshot.findZoneFor(pos).map(SafeZone::id).orElse(null);
			if (zoneId != null) {
				return new Overlay(OverlayType.UNUSED, "U:" + zoneId, 0xFFFFFF);
			}
			return Overlay.none();
		}

		CellType type = ov.type();
		if (type == CellType.HOUSE) {
			// Green = free lot, Red = assigned lot.
			if (ov.claimType() == ClaimType.PLAYER_OWNER && ov.owner() != null && !ov.owner().isBlank()) {
				String key = "H:" + ov.owner().toLowerCase(java.util.Locale.ROOT);
				return new Overlay(OverlayType.HOUSE, key, 0xe74c3c); // red
			}
			return new Overlay(OverlayType.HOUSE, "H:FREE", 0x2ecc71); // green
		}

		return switch (type) {
			case FREE -> new Overlay(OverlayType.FREE, confirmTypeKey(type), 0x1abc9c); // teal
			case STREET -> new Overlay(OverlayType.STREET, confirmTypeKey(type), 0xbdc3c7); // light gray
			case WALL -> new Overlay(OverlayType.WALL, confirmTypeKey(type), 0x7f8c8d); // darker gray
			case GATE -> new Overlay(OverlayType.GATE, confirmTypeKey(type), 0xf5b7b1); // light red/pink
			case SPAWN -> new Overlay(OverlayType.SPAWN, confirmTypeKey(type), 0xff4fd8); // pink/magenta
			case MARKET -> new Overlay(OverlayType.MARKET, confirmTypeKey(type), 0xffb3e6); // light pink
			default -> Overlay.none();
		};
	}

	private static String confirmTypeKey(CellType type) {
		// Keep keys stable for border equality checks.
		return type.name();
	}
}

