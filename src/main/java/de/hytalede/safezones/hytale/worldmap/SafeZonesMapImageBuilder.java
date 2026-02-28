package de.hytalede.safezones.hytale.worldmap;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.CellType;
import de.hytalede.safezones.core.ClaimType;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Generates a map image for one chunk index.
 *
 * <p>This is based on the default chunk map rendering pipeline, with an additional SafeZones
 * overlay for claimed chunks (community/owner) including borders.</p>
 */
public final class SafeZonesMapImageBuilder {
	private final long index;
	private final World world;
	private final MapImage image;
	private final int sampleWidth;
	private final int sampleHeight;
	private final int blockStepX;
	private final int blockStepZ;
	private final short[] heightSamples;
	private final int[] tintSamples;
	private final int[] blockSamples;
	private final short[] neighborHeightSamples;
	private final short[] fluidDepthSamples;
	private final int[] environmentSamples;
	private final int[] fluidSamples;
	private final Color outColor = new Color();

	private WorldChunk worldChunk;
	private FluidSection[] fluidSections;

	public SafeZonesMapImageBuilder(long index, int imageWidth, int imageHeight, World world) {
		this.index = index;
		this.world = world;
		this.image = new MapImage(imageWidth, imageHeight, new int[imageWidth * imageHeight]);
		this.sampleWidth = Math.min(32, this.image.width);
		this.sampleHeight = Math.min(32, this.image.height);
		this.blockStepX = Math.max(1, 32 / this.image.width);
		this.blockStepZ = Math.max(1, 32 / this.image.height);
		this.heightSamples = new short[this.sampleWidth * this.sampleHeight];
		this.tintSamples = new int[this.sampleWidth * this.sampleHeight];
		this.blockSamples = new int[this.sampleWidth * this.sampleHeight];
		this.neighborHeightSamples = new short[(this.sampleWidth + 2) * (this.sampleHeight + 2)];
		this.fluidDepthSamples = new short[this.sampleWidth * this.sampleHeight];
		this.environmentSamples = new int[this.sampleWidth * this.sampleHeight];
		this.fluidSamples = new int[this.sampleWidth * this.sampleHeight];
	}

	public long getIndex() {
		return index;
	}

	public MapImage getImage() {
		return image;
	}

	private CompletableFuture<SafeZonesMapImageBuilder> fetchChunk() {
		return world.getChunkStore().getChunkReferenceAsync(index).thenApplyAsync(ref -> {
			if (ref != null && ref.isValid()) {
				this.worldChunk = (WorldChunk) ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				ChunkColumn chunkColumn = (ChunkColumn) ref.getStore().getComponent(ref, ChunkColumn.getComponentType());
				this.fluidSections = new FluidSection[10];
				for (int y = 0; y < 10; ++y) {
					Ref<ChunkStore> sectionRef = chunkColumn.getSection(y);
					this.fluidSections[y] = world.getChunkStore().getStore().getComponent(sectionRef, FluidSection.getComponentType());
				}
				return this;
			}
			return null;
		}, world);
	}

	private CompletableFuture<SafeZonesMapImageBuilder> sampleNeighborsSync() {
		CompletableFuture<Void> north = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX(), worldChunk.getZ() - 1)).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				int z = (sampleHeight - 1) * blockStepZ;
				for (int ix = 0; ix < sampleWidth; ++ix) {
					int x = ix * blockStepX;
					neighborHeightSamples[1 + ix] = wc.getHeight(x, z);
				}
			}
		}, world);

		CompletableFuture<Void> south = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX(), worldChunk.getZ() + 1)).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				int z = 0;
				int start = (sampleHeight + 1) * (sampleWidth + 2) + 1;
				for (int ix = 0; ix < sampleWidth; ++ix) {
					int x = ix * blockStepX;
					neighborHeightSamples[start + ix] = wc.getHeight(x, z);
				}
			}
		}, world);

		CompletableFuture<Void> west = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX() - 1, worldChunk.getZ())).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				int x = (sampleWidth - 1) * blockStepX;
				for (int iz = 0; iz < sampleHeight; ++iz) {
					int z = iz * blockStepZ;
					neighborHeightSamples[(iz + 1) * (sampleWidth + 2)] = wc.getHeight(x, z);
				}
			}
		}, world);

		CompletableFuture<Void> east = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX() + 1, worldChunk.getZ())).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				int x = 0;
				for (int iz = 0; iz < sampleHeight; ++iz) {
					int z = iz * blockStepZ;
					neighborHeightSamples[(iz + 1) * (sampleWidth + 2) + sampleWidth + 1] = wc.getHeight(x, z);
				}
			}
		}, world);

		CompletableFuture<Void> northeast = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX() + 1, worldChunk.getZ() - 1)).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				neighborHeightSamples[0] = wc.getHeight(0, (sampleHeight - 1) * blockStepZ);
			}
		}, world);

		CompletableFuture<Void> northwest = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX() - 1, worldChunk.getZ() - 1)).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				neighborHeightSamples[sampleWidth + 1] = wc.getHeight((sampleWidth - 1) * blockStepX, (sampleHeight - 1) * blockStepZ);
			}
		}, world);

		CompletableFuture<Void> southeast = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX() + 1, worldChunk.getZ() + 1)).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				neighborHeightSamples[(sampleHeight + 1) * (sampleWidth + 2) + sampleWidth + 1] = wc.getHeight(0, 0);
			}
		}, world);

		CompletableFuture<Void> southwest = world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(worldChunk.getX() - 1, worldChunk.getZ() + 1)).thenAcceptAsync(ref -> {
			if (ref != null && ref.isValid()) {
				WorldChunk wc = ref.getStore().getComponent(ref, WorldChunk.getComponentType());
				neighborHeightSamples[(sampleHeight + 1) * (sampleWidth + 2)] = wc.getHeight((sampleWidth - 1) * blockStepX, 0);
			}
		}, world);

		return CompletableFuture.allOf(north, south, west, east, northeast, northwest, southeast, southwest).thenApply(v -> this);
	}

	private SafeZonesMapImageBuilder generateImageAsync() {
		for (int ix = 0; ix < sampleWidth; ++ix) {
			for (int iz = 0; iz < sampleHeight; ++iz) {
				int sampleIndex = iz * sampleWidth + ix;
				int x = ix * blockStepX;
				int z = iz * blockStepZ;
				short height = worldChunk.getHeight(x, z);
				int tint = worldChunk.getTint(x, z);
				heightSamples[sampleIndex] = height;
				tintSamples[sampleIndex] = tint;
				int blockId = worldChunk.getBlock(x, height, z);
				blockSamples[sampleIndex] = blockId;

				int fluidId = 0;
				int fluidTop = 320;
				Fluid fluid = null;
				int chunkYGround = ChunkUtil.chunkCoordinate(height);
				int chunkY = 9;

				findTop:
				while (chunkY >= 0 && chunkY >= chunkYGround) {
					FluidSection fluidSection = fluidSections[chunkY];
					if (fluidSection != null && !fluidSection.isEmpty()) {
						int minBlockY = Math.max(ChunkUtil.minBlock(chunkY), height);
						int maxBlockY = ChunkUtil.maxBlock(chunkY);
						for (int blockY = maxBlockY; blockY >= minBlockY; --blockY) {
							fluidId = fluidSection.getFluidId(x, blockY, z);
							if (fluidId != 0) {
								fluid = Fluid.getAssetMap().getAsset(fluidId);
								fluidTop = blockY;
								break findTop;
							}
						}
					}
					--chunkY;
				}

				int fluidBottom;
				findBottom:
				for (fluidBottom = height; chunkY >= 0 && chunkY >= chunkYGround; --chunkY) {
					FluidSection fluidSection = fluidSections[chunkY];
					if (fluidSection == null || fluidSection.isEmpty()) {
						fluidBottom = Math.min(ChunkUtil.maxBlock(chunkY) + 1, fluidTop);
						break;
					}
					int minBlockY = Math.max(ChunkUtil.minBlock(chunkY), height);
					int maxBlockY = Math.min(ChunkUtil.maxBlock(chunkY), fluidTop - 1);
					for (int blockY = maxBlockY; blockY >= minBlockY; --blockY) {
						int nextFluidId = fluidSection.getFluidId(x, blockY, z);
						if (nextFluidId != fluidId) {
							Fluid nextFluid = Fluid.getAssetMap().getAsset(nextFluidId);
							if (!Objects.equals(fluid != null ? fluid.getParticleColor() : null, nextFluid != null ? nextFluid.getParticleColor() : null)) {
								fluidBottom = blockY + 1;
								break findBottom;
							}
						}
					}
				}

				short fluidDepth = fluidId != 0 ? (short) (fluidTop - fluidBottom + 1) : 0;
				int environmentId = worldChunk.getBlockChunk().getEnvironment(x, fluidTop, z);
				fluidDepthSamples[sampleIndex] = fluidDepth;
				environmentSamples[sampleIndex] = environmentId;
				fluidSamples[sampleIndex] = fluidId;
			}
		}

		float imageToSampleRatioWidth = (float) sampleWidth / (float) image.width;
		float imageToSampleRatioHeight = (float) sampleHeight / (float) image.height;
		int blockPixelWidth = Math.max(1, image.width / sampleWidth);
		int blockPixelHeight = Math.max(1, image.height / sampleHeight);

		for (int iz = 0; iz < sampleHeight; ++iz) {
			System.arraycopy(heightSamples, iz * sampleWidth, neighborHeightSamples, (iz + 1) * (sampleWidth + 2) + 1, sampleWidth);
		}

		// SafeZones overlay context (chunk-based, using current snapshot)
		SafeZonesSnapshot snapshot = SafeZonesWorldMapState.snapshotOrEmpty();
		int chunkX = ChunkUtil.xOfChunkIndex(index);
		int chunkZ = ChunkUtil.zOfChunkIndex(index);
		// SafeZones use 16x16 cells; one 32x32 map chunk contains 2x2 cells.
		final int baseCellX = chunkX * 2;
		final int baseCellZ = chunkZ * 2;

		// Cells within this map chunk:
		Overlay cell00 = overlayForChunk(snapshot, new ChunkPos(baseCellX, baseCellZ));         // NW
		Overlay cell10 = overlayForChunk(snapshot, new ChunkPos(baseCellX + 1, baseCellZ));     // NE
		Overlay cell01 = overlayForChunk(snapshot, new ChunkPos(baseCellX, baseCellZ + 1));     // SW
		Overlay cell11 = overlayForChunk(snapshot, new ChunkPos(baseCellX + 1, baseCellZ + 1)); // SE

		// Neighbor cells (needed for borders on the outer chunk edges)
		Overlay west0 = overlayForChunk(snapshot, new ChunkPos(baseCellX - 1, baseCellZ));
		Overlay west1 = overlayForChunk(snapshot, new ChunkPos(baseCellX - 1, baseCellZ + 1));
		Overlay east0 = overlayForChunk(snapshot, new ChunkPos(baseCellX + 2, baseCellZ));
		Overlay east1 = overlayForChunk(snapshot, new ChunkPos(baseCellX + 2, baseCellZ + 1));
		Overlay north0 = overlayForChunk(snapshot, new ChunkPos(baseCellX, baseCellZ - 1));
		Overlay north1 = overlayForChunk(snapshot, new ChunkPos(baseCellX + 1, baseCellZ - 1));
		Overlay south0 = overlayForChunk(snapshot, new ChunkPos(baseCellX, baseCellZ + 2));
		Overlay south1 = overlayForChunk(snapshot, new ChunkPos(baseCellX + 1, baseCellZ + 2));

		// Zone ids (used to draw full zone borders even when cell types match)
		String zone00 = snapshot.findZoneFor(new ChunkPos(baseCellX, baseCellZ)).map(SafeZone::id).orElse(null);
		String zone10 = snapshot.findZoneFor(new ChunkPos(baseCellX + 1, baseCellZ)).map(SafeZone::id).orElse(null);
		String zone01 = snapshot.findZoneFor(new ChunkPos(baseCellX, baseCellZ + 1)).map(SafeZone::id).orElse(null);
		String zone11 = snapshot.findZoneFor(new ChunkPos(baseCellX + 1, baseCellZ + 1)).map(SafeZone::id).orElse(null);

		String zWest0 = snapshot.findZoneFor(new ChunkPos(baseCellX - 1, baseCellZ)).map(SafeZone::id).orElse(null);
		String zWest1 = snapshot.findZoneFor(new ChunkPos(baseCellX - 1, baseCellZ + 1)).map(SafeZone::id).orElse(null);
		String zEast0 = snapshot.findZoneFor(new ChunkPos(baseCellX + 2, baseCellZ)).map(SafeZone::id).orElse(null);
		String zEast1 = snapshot.findZoneFor(new ChunkPos(baseCellX + 2, baseCellZ + 1)).map(SafeZone::id).orElse(null);
		String zNorth0 = snapshot.findZoneFor(new ChunkPos(baseCellX, baseCellZ - 1)).map(SafeZone::id).orElse(null);
		String zNorth1 = snapshot.findZoneFor(new ChunkPos(baseCellX + 1, baseCellZ - 1)).map(SafeZone::id).orElse(null);
		String zSouth0 = snapshot.findZoneFor(new ChunkPos(baseCellX, baseCellZ + 2)).map(SafeZone::id).orElse(null);
		String zSouth1 = snapshot.findZoneFor(new ChunkPos(baseCellX + 1, baseCellZ + 2)).map(SafeZone::id).orElse(null);

		final int splitX = image.width / 2;
		final int splitZ = image.height / 2;

		SafeZonesHytalePlugin.GridMode gridMode = SafeZonesWorldMapState.getGridMode();
		if (!gridMode.isEnabled()) {
			return this;
		}
		boolean simple = gridMode == SafeZonesHytalePlugin.GridMode.SIMPLE;
		boolean detailed = gridMode == SafeZonesHytalePlugin.GridMode.DETAILED;
		boolean filled = gridMode == SafeZonesHytalePlugin.GridMode.FILLED;
		int filledBorderSize = 2;
		int simpleBorderSize = 1;
		boolean drawCrosses = detailed || filled;
		boolean drawLabels = detailed || filled;

		for (int ix = 0; ix < image.width; ++ix) {
			for (int iz = 0; iz < image.height; ++iz) {
				int sampleX = Math.min((int) ((float) ix * imageToSampleRatioWidth), sampleWidth - 1);
				int sampleZ = Math.min((int) ((float) iz * imageToSampleRatioHeight), sampleHeight - 1);
				int sampleIndex = sampleZ * sampleWidth + sampleX;
				int blockPixelX = ix % blockPixelWidth;
				int blockPixelZ = iz % blockPixelHeight;

				short height = heightSamples[sampleIndex];
				int tint = tintSamples[sampleIndex];
				int blockId = blockSamples[sampleIndex];

				getBlockColor(blockId, tint, outColor);

				// Determine the 16x16-cell overlay for this pixel (2x2 per chunk).
				boolean eastHalf = ix >= splitX;
				boolean southHalf = iz >= splitZ;
				Overlay overlay = (!eastHalf && !southHalf) ? cell00
						: (eastHalf && !southHalf) ? cell10
						: (!eastHalf) ? cell01
						: cell11;

				if (filled) {
					// Filled mode: tint the entire area and boost tint on borders vs neighboring cells.
					if (overlay.type != OverlayType.NONE) {
						boolean isBorder = isOverlayBorderPixel16(ix, iz, image.width, image.height, splitX, splitZ, filledBorderSize,
								overlay,
								cell00, cell10, cell01, cell11,
								west0, west1, east0, east1,
								north0, north1, south0, south1
						);
						applyOverlayTint(overlay.type, overlay.colorRgb, outColor, isBorder);
					}
				} else if (simple || detailed) {
					// Border-only mode: same neighbor-comparison logic as FILLED, but without filling areas.
					if (overlay.type != OverlayType.NONE) {
						boolean isBorder = isOverlayBorderPixel16(ix, iz, image.width, image.height, splitX, splitZ, simpleBorderSize,
								overlay,
								cell00, cell10, cell01, cell11,
								west0, west1, east0, east1,
								north0, north1, south0, south1
						);
						if (isBorder) {
							applyOverlayTint(overlay.type, overlay.colorRgb, outColor, true);
						}
					}
				}

				short n = neighborHeightSamples[sampleZ * (sampleWidth + 2) + sampleX + 1];
				short s = neighborHeightSamples[(sampleZ + 2) * (sampleWidth + 2) + sampleX + 1];
				short w = neighborHeightSamples[(sampleZ + 1) * (sampleWidth + 2) + sampleX];
				short e = neighborHeightSamples[(sampleZ + 1) * (sampleWidth + 2) + sampleX + 2];
				short nw = neighborHeightSamples[sampleZ * (sampleWidth + 2) + sampleX];
				short ne = neighborHeightSamples[sampleZ * (sampleWidth + 2) + sampleX + 2];
				short sw = neighborHeightSamples[(sampleZ + 2) * (sampleWidth + 2) + sampleX];
				short se = neighborHeightSamples[(sampleZ + 2) * (sampleWidth + 2) + sampleX + 2];

				float shade = shadeFromHeights(blockPixelX, blockPixelZ, blockPixelWidth, blockPixelHeight, height, n, s, w, e, nw, ne, sw, se);
				outColor.multiply(shade);

				if (height < 320) {
					int fluidId = fluidSamples[sampleIndex];
					if (fluidId != 0) {
						short fluidDepth = fluidDepthSamples[sampleIndex];
						int environmentId = environmentSamples[sampleIndex];
						getFluidColor(fluidId, environmentId, fluidDepth, outColor);
					}
				}

				image.data[iz * image.width + ix] = outColor.pack();
			}
		}

		// Mark 16x16 cell corners (2x2 per chunk) so it's easy to count sub-cells.
		if (drawCrosses) {
			drawCellCornerMarkers(image, 0, 0, splitX, splitZ, cell00);
			drawCellCornerMarkers(image, 1, 0, splitX, splitZ, cell10);
			drawCellCornerMarkers(image, 0, 1, splitX, splitZ, cell01);
			drawCellCornerMarkers(image, 1, 1, splitX, splitZ, cell11);
		}
		// Labels should be rendered on top of fill/crosses (FILLED only)
		if (drawLabels) {
			drawCellTypeLabel(image, 0, 0, splitX, splitZ, labelForChunkOverride(snapshot, new ChunkPos(baseCellX, baseCellZ)));
			drawCellTypeLabel(image, 1, 0, splitX, splitZ, labelForChunkOverride(snapshot, new ChunkPos(baseCellX + 1, baseCellZ)));
			drawCellTypeLabel(image, 0, 1, splitX, splitZ, labelForChunkOverride(snapshot, new ChunkPos(baseCellX, baseCellZ + 1)));
			drawCellTypeLabel(image, 1, 1, splitX, splitZ, labelForChunkOverride(snapshot, new ChunkPos(baseCellX + 1, baseCellZ + 1)));
		}

		return this;
	}

	private static String labelForChunkOverride(SafeZonesSnapshot snapshot, ChunkPos pos) {
		ChunkOverride ov = snapshot.chunkOverrides().get(pos);
		if (ov == null || ov.type() == null || ov.type() == CellType.NONE) {
			return null;
		}
		CellType type = ov.type();
		return switch (type) {
			case FREE -> (ov.claimType() == ClaimType.COMMUNITY) ? "C" : "F";
			case HOUSE -> {
				// Claimed house: no label.
				if (ov.claimType() == ClaimType.PLAYER_OWNER && ov.owner() != null && !ov.owner().isBlank()) {
					yield null;
				}
				yield "H";
			}
			case WALL -> "W";
			case STREET -> "ST";
			case GATE -> "G";
			case MARKET -> "M";
			case SPAWN -> "SP";
			default -> null;
		};
	}

	private static void drawCellTypeLabel(MapImage image, int cellXIdx, int cellZIdx, int splitX, int splitZ, String label) {
		if (label == null || label.isBlank() || image == null || image.data == null) {
			return;
		}
		int w = image.width;
		int h = image.height;
		if (w <= 0 || h <= 0) {
			return;
		}

		int x0 = (cellXIdx == 0) ? 0 : splitX;
		int x1 = (cellXIdx == 0) ? Math.max(0, splitX - 1) : w - 1;
		int z0 = (cellZIdx == 0) ? 0 : splitZ;
		int z1 = (cellZIdx == 0) ? Math.max(0, splitZ - 1) : h - 1;

		int cx = (x0 + x1) / 2;
		int cz = (z0 + z1) / 2;
		drawTextCentered5x7(image, cx, cz, label);
	}

	private static void drawTextCentered5x7(MapImage image, int cx, int cz, String text) {
		text = text.trim().toUpperCase(java.util.Locale.ROOT);
		if (text.isEmpty()) {
			return;
		}
		final int glyphW = 5;
		final int glyphH = 7;
		final int gap = 1;
		int len = text.length();
		int totalW = len * glyphW + (len - 1) * gap;
		int startX = cx - totalW / 2;
		int startZ = cz - glyphH / 2;

		int fg = chooseReadableTextColor(image, cx, cz);

		for (int i = 0; i < len; i++) {
			char ch = text.charAt(i);
			int[] rows = glyph5x7(ch);
			if (rows == null) {
				continue;
			}
			int gx = startX + i * (glyphW + gap);
			for (int r = 0; r < glyphH; r++) {
				int bits = rows[r];
				for (int c = 0; c < glyphW; c++) {
					if (((bits >> (glyphW - 1 - c)) & 1) != 0) {
						setPixel(image, gx + c, startZ + r, fg);
					}
				}
			}
		}
	}

	private static int chooseReadableTextColor(MapImage image, int x, int z) {
		if (x < 0 || z < 0 || x >= image.width || z >= image.height) {
			return packRgbA(0xFFFFFF, 255);
		}
		int packed = image.data[z * image.width + x];
		int r = packed >> 24 & 255;
		int g = packed >> 16 & 255;
		int b = packed >> 8 & 255;
		int lum = (int) (0.2126f * r + 0.7152f * g + 0.0722f * b);
		return lum > 140 ? packRgbA(0x000000, 255) : packRgbA(0xFFFFFF, 255);
	}

	/**
	 * 5x7 glyph rows, each row is 5 bits (MSB on the left).
	 */
	private static int[] glyph5x7(char ch) {
		return switch (ch) {
			case 'F' -> new int[]{
					0b11111,
					0b10000,
					0b11110,
					0b10000,
					0b10000,
					0b10000,
					0b10000
			};
			case 'C' -> new int[]{
					0b01111,
					0b10000,
					0b10000,
					0b10000,
					0b10000,
					0b10000,
					0b01111
			};
			case 'H' -> new int[]{
					0b10001,
					0b10001,
					0b10001,
					0b11111,
					0b10001,
					0b10001,
					0b10001
			};
			case 'W' -> new int[]{
					0b10001,
					0b10001,
					0b10001,
					0b10101,
					0b10101,
					0b11011,
					0b10001
			};
			case 'S' -> new int[]{
					0b01111,
					0b10000,
					0b10000,
					0b01110,
					0b00001,
					0b00001,
					0b11110
			};
			case 'T' -> new int[]{
					0b11111,
					0b00100,
					0b00100,
					0b00100,
					0b00100,
					0b00100,
					0b00100
			};
			case 'G' -> new int[]{
					0b01111,
					0b10000,
					0b10000,
					0b10011,
					0b10001,
					0b10001,
					0b01111
			};
			case 'M' -> new int[]{
					0b10001,
					0b11011,
					0b10101,
					0b10001,
					0b10001,
					0b10001,
					0b10001
			};
			case 'P' -> new int[]{
					0b11110,
					0b10001,
					0b10001,
					0b11110,
					0b10000,
					0b10000,
					0b10000
			};
			default -> null;
		};
	}

	private static void drawCellCornerMarkers(MapImage image, int cellXIdx, int cellZIdx, int splitX, int splitZ, Overlay overlay) {
		if (overlay == null || overlay.type == OverlayType.NONE) {
			return;
		}
		if (image == null || image.data == null) {
			return;
		}
		int w = image.width;
		int h = image.height;
		if (w <= 0 || h <= 0) {
			return;
		}

		int x0 = (cellXIdx == 0) ? 0 : splitX;
		int x1 = (cellXIdx == 0) ? Math.max(0, splitX - 1) : w - 1;
		int z0 = (cellZIdx == 0) ? 0 : splitZ;
		int z1 = (cellZIdx == 0) ? Math.max(0, splitZ - 1) : h - 1;

		int base = packRgbA(overlay.colorRgb, 255);
		int dark = packRgbA(darken(overlay.colorRgb, 0.6f), 255);

		// NW
		drawCornerCross(image, x0, z0, base, dark, 3);
		// NE
		drawCornerCross(image, x1, z0, base, dark, 3);
		// SW
		drawCornerCross(image, x0, z1, base, dark, 3);
		// SE
		drawCornerCross(image, x1, z1, base, dark, 3);
	}

	private static void drawCornerCross(MapImage image, int x, int z, int base, int dark, int r) {
		setPixel(image, x, z, base);
		for (int i = 1; i <= r; i++) {
			setPixel(image, x + i, z, dark);
			setPixel(image, x - i, z, dark);
			setPixel(image, x, z + i, dark);
			setPixel(image, x, z - i, dark);
		}
	}

	private static void setPixel(MapImage image, int x, int y, int rgbaPacked) {
		if (x < 0 || y < 0 || x >= image.width || y >= image.height) {
			return;
		}
		image.data[y * image.width + x] = rgbaPacked;
	}

	private static int packRgbA(int rgb, int a) {
		int r = rgb >> 16 & 255;
		int g = rgb >> 8 & 255;
		int b = rgb & 255;
		return (r & 255) << 24 | (g & 255) << 16 | (b & 255) << 8 | a & 255;
	}

	private static int darken(int rgb, float multiplier) {
		int r = rgb >> 16 & 255;
		int g = rgb >> 8 & 255;
		int b = rgb & 255;
		r = Math.min(255, Math.max(0, (int) (r * multiplier)));
		g = Math.min(255, Math.max(0, (int) (g * multiplier)));
		b = Math.min(255, Math.max(0, (int) (b * multiplier)));
		return (r & 255) << 16 | (g & 255) << 8 | (b & 255);
	}

	private static boolean isBorderPixel16(
			int ix, int iz, int w, int h, int splitX, int splitZ, int borderSize, boolean detailed,
			Overlay self,
			Overlay cell00, Overlay cell10, Overlay cell01, Overlay cell11,
			Overlay west0, Overlay west1, Overlay east0, Overlay east1,
			Overlay north0, Overlay north1, Overlay south0, Overlay south1
	) {
		boolean eastHalf = ix >= splitX;
		boolean southHalf = iz >= splitZ;

		// Outer chunk edges
		if (ix <= borderSize) {
			Overlay neighbor = southHalf ? west1 : west0;
			if (!sameOverlay(self, neighbor)) return true;
		}
		if (ix >= w - borderSize - 1) {
			Overlay neighbor = southHalf ? east1 : east0;
			if (!sameOverlay(self, neighbor)) return true;
		}
		if (iz <= borderSize) {
			Overlay neighbor = eastHalf ? north1 : north0;
			if (!sameOverlay(self, neighbor)) return true;
		}
		if (iz >= h - borderSize - 1) {
			Overlay neighbor = eastHalf ? south1 : south0;
			if (!sameOverlay(self, neighbor)) return true;
		}

		// Detailed mode only adds corner crosses (no internal lines).

		return false;
	}

	private static boolean isZoneBorderPixel16(
			int ix, int iz, int w, int h, int splitX, int splitZ, int borderSize,
			String selfZone,
			String zone00, String zone10, String zone01, String zone11,
			String west0, String west1, String east0, String east1,
			String north0, String north1, String south0, String south1
	) {
		boolean eastHalf = ix >= splitX;
		boolean southHalf = iz >= splitZ;

		// We have 2x2 SafeZones cells (16x16 each) inside one world/map chunk (32x32).
		// To draw full borders reliably, decide borders per-cell-edge and compare against the correct neighbor zone.
		//
		// Cell selection:
		// - top-left  (00): !eastHalf && !southHalf
		// - top-right (10):  eastHalf && !southHalf
		// - bot-left  (01): !eastHalf &&  southHalf
		// - bot-right (11):  eastHalf &&  southHalf

		// West edge of current cell
		int cellWestEdge = eastHalf ? splitX : 0;
		if (ix >= cellWestEdge && ix <= cellWestEdge + borderSize) {
			String neighbor = (!eastHalf)
					? (southHalf ? west1 : west0) // outside chunk
					: (southHalf ? zone01 : zone00); // adjacent cell inside chunk
			if (!Objects.equals(selfZone, neighbor)) return true;
		}

		// East edge of current cell
		int cellEastEdge = eastHalf ? (w - 1) : (splitX - 1);
		if (ix >= cellEastEdge - borderSize && ix <= cellEastEdge) {
			String neighbor = (eastHalf)
					? (southHalf ? east1 : east0) // outside chunk
					: (southHalf ? zone11 : zone10); // adjacent cell inside chunk
			if (!Objects.equals(selfZone, neighbor)) return true;
		}

		// North (top) edge of current cell
		int cellNorthEdge = southHalf ? splitZ : 0;
		if (iz >= cellNorthEdge && iz <= cellNorthEdge + borderSize) {
			String neighbor = (!southHalf)
					? (eastHalf ? north1 : north0) // outside chunk
					: (eastHalf ? zone10 : zone00); // adjacent cell inside chunk
			if (!Objects.equals(selfZone, neighbor)) return true;
		}

		// South (bottom) edge of current cell
		int cellSouthEdge = southHalf ? (h - 1) : (splitZ - 1);
		if (iz >= cellSouthEdge - borderSize && iz <= cellSouthEdge) {
			String neighbor = (southHalf)
					? (eastHalf ? south1 : south0) // outside chunk
					: (eastHalf ? zone11 : zone01); // adjacent cell inside chunk
			if (!Objects.equals(selfZone, neighbor)) return true;
		}

		return false;
	}

	private static void applyZoneBorderTint(Color outColor, int alpha) {
		int or = 245;
		int og = 245;
		int ob = 245;
		outColor.r = (outColor.r * (255 - alpha) + or * alpha) / 255;
		outColor.g = (outColor.g * (255 - alpha) + og * alpha) / 255;
		outColor.b = (outColor.b * (255 - alpha) + ob * alpha) / 255;
		outColor.a = 255;
	}

	private static boolean isOverlayBorderPixel16(
			int ix, int iz, int w, int h, int splitX, int splitZ, int borderSize,
			Overlay self,
			Overlay cell00, Overlay cell10, Overlay cell01, Overlay cell11,
			Overlay west0, Overlay west1, Overlay east0, Overlay east1,
			Overlay north0, Overlay north1, Overlay south0, Overlay south1
	) {
		boolean eastHalf = ix >= splitX;
		boolean southHalf = iz >= splitZ;

		// West edge of current 16x16 cell
		int cellWestEdge = eastHalf ? splitX : 0;
		if (ix >= cellWestEdge && ix <= cellWestEdge + borderSize) {
			Overlay neighbor = (!eastHalf)
					? (southHalf ? west1 : west0)
					: (southHalf ? cell01 : cell00);
			if (!sameOverlay(self, neighbor != null ? neighbor : Overlay.none())) return true;
		}

		// East edge
		int cellEastEdge = eastHalf ? (w - 1) : (splitX - 1);
		if (ix >= cellEastEdge - borderSize && ix <= cellEastEdge) {
			Overlay neighbor = (eastHalf)
					? (southHalf ? east1 : east0)
					: (southHalf ? cell11 : cell10);
			if (!sameOverlay(self, neighbor != null ? neighbor : Overlay.none())) return true;
		}

		// North edge
		int cellNorthEdge = southHalf ? splitZ : 0;
		if (iz >= cellNorthEdge && iz <= cellNorthEdge + borderSize) {
			Overlay neighbor = (!southHalf)
					? (eastHalf ? north1 : north0)
					: (eastHalf ? cell10 : cell00);
			if (!sameOverlay(self, neighbor != null ? neighbor : Overlay.none())) return true;
		}

		// South edge
		int cellSouthEdge = southHalf ? (h - 1) : (splitZ - 1);
		if (iz >= cellSouthEdge - borderSize && iz <= cellSouthEdge) {
			Overlay neighbor = (southHalf)
					? (eastHalf ? south1 : south0)
					: (eastHalf ? cell11 : cell01);
			if (!sameOverlay(self, neighbor != null ? neighbor : Overlay.none())) return true;
		}

		return false;
	}

	private static boolean sameOverlay(Overlay a, Overlay b) {
		return a.type == b.type && Objects.equals(a.groupKey, b.groupKey);
	}

	private static Overlay overlayForChunk(SafeZonesSnapshot snapshot, ChunkPos pos) {
		// Hard gate: when /safezone show is OFF globally, do not render any overlay at all.
		if (!SafeZonesWorldMapState.isShowEnabled()) {
			return Overlay.none();
		}
		ChunkOverride ov = snapshot.chunkOverrides().get(pos);
		if (ov == null || ov.type() == null || ov.type() == CellType.NONE) {
			// No plot-type definition:
			// If we're inside any SafeZone rectangle, show the "unused" area tint so /safezone show displays ALL areas.
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
			case FREE -> new Overlay(OverlayType.FREE, "FREE", 0x1abc9c); // teal (community free area)
			case STREET -> new Overlay(OverlayType.STREET, "STREET", 0xbdc3c7); // light gray
			case WALL -> new Overlay(OverlayType.WALL, "WALL", 0x7f8c8d); // darker gray
			case GATE -> new Overlay(OverlayType.GATE, "GATE", 0xf5b7b1); // light red/pink
			case SPAWN -> new Overlay(OverlayType.SPAWN, "SPAWN", 0xff4fd8); // pink/magenta
			case MARKET -> new Overlay(OverlayType.MARKET, "MARKET", 0xffb3e6); // light pink
			default -> Overlay.none();
		};
	}

	private static float shadeFromHeights(int blockPixelX, int blockPixelZ, int blockPixelWidth, int blockPixelHeight, short height, short north, short south, short west, short east, short northWest, short northEast, short southWest, short southEast) {
		float u = ((float) blockPixelX + 0.5F) / (float) blockPixelWidth;
		float v = ((float) blockPixelZ + 0.5F) / (float) blockPixelHeight;
		float ud = (u + v) / 2.0F;
		float vd = (1.0F - u + v) / 2.0F;
		float dhdx1 = (float) (height - west) * (1.0F - u) + (float) (east - height) * u;
		float dhdz1 = (float) (height - north) * (1.0F - v) + (float) (south - height) * v;
		float dhdx2 = (float) (height - northWest) * (1.0F - ud) + (float) (southEast - height) * ud;
		float dhdz2 = (float) (height - northEast) * (1.0F - vd) + (float) (southWest - height) * vd;
		float dhdx = dhdx1 * 2.0F + dhdx2;
		float dhdz = dhdz1 * 2.0F + dhdz2;
		float dy = 3.0F;
		float invS = 1.0F / (float) Math.sqrt((double) (dhdx * dhdx + dy * dy + dhdz * dhdz));
		float nx = dhdx * invS;
		float ny = dy * invS;
		float nz = dhdz * invS;
		float lx = -0.2F;
		float ly = 0.8F;
		float lz = 0.5F;
		float invL = 1.0F / (float) Math.sqrt((double) (lx * lx + ly * ly + lz * lz));
		lx *= invL;
		ly *= invL;
		lz *= invL;
		float lambert = Math.max(0.0F, nx * lx + ny * ly + nz * lz);
		float ambient = 0.4F;
		float diffuse = 0.6F;
		return ambient + diffuse * lambert;
	}

	private static void getBlockColor(int blockId, int biomeTintColor, Color outColor) {
		BlockType block = BlockType.getAssetMap().getAsset(blockId);
		int biomeTintR = biomeTintColor >> 16 & 255;
		int biomeTintG = biomeTintColor >> 8 & 255;
		int biomeTintB = biomeTintColor & 255;
		com.hypixel.hytale.protocol.Color[] tintUp = block.getTintUp();
		boolean hasTint = tintUp != null && tintUp.length > 0;
		int selfTintR = hasTint ? tintUp[0].red & 255 : 255;
		int selfTintG = hasTint ? tintUp[0].green & 255 : 255;
		int selfTintB = hasTint ? tintUp[0].blue & 255 : 255;
		float biomeTintMultiplier = (float) block.getBiomeTintUp() / 100.0F;
		int tintColorR = (int) ((float) selfTintR + (float) (biomeTintR - selfTintR) * biomeTintMultiplier);
		int tintColorG = (int) ((float) selfTintG + (float) (biomeTintG - selfTintG) * biomeTintMultiplier);
		int tintColorB = (int) ((float) selfTintB + (float) (biomeTintB - selfTintB) * biomeTintMultiplier);
		com.hypixel.hytale.protocol.Color particleColor = block.getParticleColor();
		if (particleColor != null && biomeTintMultiplier < 1.0F) {
			tintColorR = tintColorR * (particleColor.red & 255) / 255;
			tintColorG = tintColorG * (particleColor.green & 255) / 255;
			tintColorB = tintColorB * (particleColor.blue & 255) / 255;
		}
		outColor.r = tintColorR & 255;
		outColor.g = tintColorG & 255;
		outColor.b = tintColorB & 255;
		outColor.a = 255;
	}

	private static void applyOverlayTint(OverlayType type, int rgb, Color outColor, boolean isBorder) {
		// Blend overlay color onto the existing map color so it looks "transparent".
		// Borders get a stronger tint so areas are readable at a glance.
		int alpha;
		if (type == OverlayType.UNUSED) {
			// Make unused area visible (still translucent).
			alpha = isBorder ? 190 : 120;
		} else {
			alpha = isBorder ? 140 : 80; // 0..255
		}
		int or = rgb >> 16 & 255;
		int og = rgb >> 8 & 255;
		int ob = rgb & 255;
		outColor.r = (outColor.r * (255 - alpha) + or * alpha) / 255;
		outColor.g = (outColor.g * (255 - alpha) + og * alpha) / 255;
		outColor.b = (outColor.b * (255 - alpha) + ob * alpha) / 255;
		outColor.a = 255;
	}

	private static void getFluidColor(int fluidId, int environmentId, int fluidDepth, Color outColor) {
		int tintColorR = 255;
		int tintColorG = 255;
		int tintColorB = 255;
		Environment environment = Environment.getAssetMap().getAsset(environmentId);
		com.hypixel.hytale.protocol.Color waterTint = environment.getWaterTint();
		if (waterTint != null) {
			tintColorR = tintColorR * (waterTint.red & 255) / 255;
			tintColorG = tintColorG * (waterTint.green & 255) / 255;
			tintColorB = tintColorB * (waterTint.blue & 255) / 255;
		}
		Fluid fluid = Fluid.getAssetMap().getAsset(fluidId);
		com.hypixel.hytale.protocol.Color particleColor = fluid.getParticleColor();
		if (particleColor != null) {
			tintColorR = tintColorR * (particleColor.red & 255) / 255;
			tintColorG = tintColorG * (particleColor.green & 255) / 255;
			tintColorB = tintColorB * (particleColor.blue & 255) / 255;
		}
		float depthMultiplier = Math.min(1.0F, 1.0F / (float) fluidDepth);
		outColor.r = (int) ((float) tintColorR + (float) ((outColor.r & 255) - tintColorR) * depthMultiplier) & 255;
		outColor.g = (int) ((float) tintColorG + (float) ((outColor.g & 255) - tintColorG) * depthMultiplier) & 255;
		outColor.b = (int) ((float) tintColorB + (float) ((outColor.b & 255) - tintColorB) * depthMultiplier) & 255;
	}

	public static CompletableFuture<SafeZonesMapImageBuilder> build(long index, int imageWidth, int imageHeight, World world) {
		return CompletableFuture
				.completedFuture(new SafeZonesMapImageBuilder(index, imageWidth, imageHeight, world))
				.thenCompose(SafeZonesMapImageBuilder::fetchChunk)
				.thenCompose(builder -> builder != null ? builder.sampleNeighborsSync() : CompletableFuture.completedFuture(null))
				.thenApplyAsync(builder -> builder != null ? builder.generateImageAsync() : null);
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

	private static final class Color {
		int r, g, b, a;

		int pack() {
			return (r & 255) << 24 | (g & 255) << 16 | (b & 255) << 8 | a & 255;
		}

		void multiply(float value) {
			r = Math.min(255, Math.max(0, (int) ((float) r * value)));
			g = Math.min(255, Math.max(0, (int) ((float) g * value)));
			b = Math.min(255, Math.max(0, (int) ((float) b * value)));
		}
	}
}

