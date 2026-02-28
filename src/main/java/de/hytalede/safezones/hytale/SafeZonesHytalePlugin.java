package de.hytalede.safezones.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.PermissionHolder;
import com.hypixel.hytale.server.core.asset.type.gameplay.CraftingConfig;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.hytalede.safezones.commands.CommandContext;
import de.hytalede.safezones.commands.CommandResponse;
import de.hytalede.safezones.commands.SafeZoneCommands;
import de.hytalede.safezones.commands.SelectionStore;
import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.config.SafeZonesConfigLoader;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.Decision;
import de.hytalede.safezones.core.PlayerNames;
import de.hytalede.safezones.core.Role;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneRect;
import de.hytalede.safezones.core.ZoneActionRequest;
import de.hytalede.safezones.core.ZoneActionType;
import de.hytalede.safezones.core.ZoneEngine;
import de.hytalede.safezones.hytale.commands.SafeZoneHytaleCommand;
import de.hytalede.safezones.hytale.systems.SafeZonesEnterTitleSystems;
import de.hytalede.safezones.hytale.systems.SafeZonesProtectionSystems;
import de.hytalede.safezones.hytale.systems.SafeZonesShowBordersSystems;
import de.hytalede.safezones.hytale.systems.SafeZonesShowBenchSystems;
import de.hytalede.safezones.hytale.systems.SafeZonesStreetSpeedSystems;
import de.hytalede.safezones.hytale.worldmap.SafeZonesBorderOverlayWorldMap;
import de.hytalede.safezones.hytale.worldmap.SafeZonesChunkWorldMap;
import de.hytalede.safezones.hytale.worldmap.SafeZonesShowMarkerProvider;
import de.hytalede.safezones.hytale.worldmap.SafeZonesWorldMapProvider;
import de.hytalede.safezones.hytale.worldmap.SafeZonesWorldMapState;
import de.hytalede.safezones.hytale.worldmap.SafeZonesWorldMapUpdateTickingSystem;
import de.hytalede.safezones.storage.SafeZonesRepository;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.IWorldMapProvider;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapLoadException;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Hytale runtime adapter for the SafeZones core.
 *
 * <p>This plugin:
 * <ul>
 *   <li>Loads {@code config.json} + {@code zones.json} from the plugin data directory</li>
 *   <li>Caches an in-memory {@link SafeZonesSnapshot} for fast event decisions</li>
 *   <li>Registers basic protection hooks (break/place/use/drop/pickup)</li>
 *   <li>Exposes {@code /safezone ...} (delegating to core {@link SafeZoneCommands})</li>
 * </ul>
 */
public final class SafeZonesHytalePlugin extends JavaPlugin {
	private static final String DEFAULT_CONFIG_RESOURCE = "/config.json";
	private static final String DEFAULT_ZONES_RESOURCE = "/zones.json";
	private static final String CONFIG_FILENAME = "config.json";
	private static final String ZONES_FILENAME = "zones.json";

	/**
	 * Role permissions (assign these via Hytale group config).
	 *
	 * <p>Resolution order: admin -> mod -> community builder.</p>
	 */
	public static final String PERM_ROLE_ADMIN = "hytalede.safezones.role.admin";
	public static final String PERM_ROLE_MOD = "hytalede.safezones.role.mod";
	public static final String PERM_ROLE_COMMUNITY_BUILDER = "hytalede.safezones.role.communitybuilder";
	public static final String PERM_PLACE_FLUID = "hytalede.safezones.placefluid";

	private final AtomicReference<SafeZonesSnapshot> snapshotRef = new AtomicReference<>(new SafeZonesSnapshot(java.util.List.of(), java.util.Map.of()));
	private final ConcurrentHashMap<UUID, ShowSettings> showSettingsByPlayer = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, IWorldMap> previousWorldMapGeneratorsByWorld = new ConcurrentHashMap<>();
	private final AtomicBoolean globalShowWorldMapEnabled = new AtomicBoolean(false);
	private final AtomicReference<GridMode> globalShowGridMode = new AtomicReference<>(GridMode.OFF);
	private final Object dirtyWorldMapLock = new Object();
	private final LongOpenHashSet dirtyWorldMapChunks = new LongOpenHashSet();

	private final SelectionStore selectionStore = new SelectionStore();
	private Path configPath;
	private Path zonesPath;

	private SafeZonesConfig config;
	private SafeZonesRepository repository;
	private SafeZoneCommands commands;
	private ZoneEngine engine;

	public SafeZonesHytalePlugin(JavaPluginInit init) {
		super(Objects.requireNonNull(init, "init"));
	}

	@Override
	protected void setup() {
		this.configPath = getDataDirectory().resolve(CONFIG_FILENAME);
		this.zonesPath = getDataDirectory().resolve(ZONES_FILENAME);

		ensureDefaultResource(this.configPath, DEFAULT_CONFIG_RESOURCE);
		ensureDefaultResource(this.zonesPath, DEFAULT_ZONES_RESOURCE);

		// Validate config early so broken JSON doesn't crash later in events.
		final SafeZonesConfig loadedConfig;
		try {
			loadedConfig = SafeZonesConfigLoader.load(this.configPath);
		} catch (Exception e) {
			getLogger().at(Level.SEVERE).withCause(e).log(
					"Invalid SafeZones config (%s). Plugin will not start until fixed.",
					this.configPath.toAbsolutePath().toString()
			);
			this.config = null;
			this.repository = null;
			this.commands = null;
			this.engine = null;
			return;
		}

		this.config = loadedConfig;
		this.repository = new SafeZonesRepository(loadedConfig, this.zonesPath);
		this.commands = new SafeZoneCommands(loadedConfig, repository, selectionStore);
		this.engine = new ZoneEngine();

		// Allow world map provider/generator to query current snapshot.
		SafeZonesWorldMapState.setSnapshotSupplier(this::getSnapshot);
		SafeZonesWorldMapState.setShowEnabledSupplier(globalShowWorldMapEnabled::get);
		SafeZonesWorldMapState.setGridModeSupplier(globalShowGridMode::get);

		reloadSnapshotSafely();

		// /safezone ...
		this.getCommandRegistry().registerCommand(new SafeZoneHytaleCommand(this));

		// World map:
		// We do NOT apply the SafeZones world map generator globally anymore because it is world-global
		// (not per-player). SafeZones visualization on the map must only be visible for staff that toggled
		// /safezone show, which we implement via per-player map markers instead.
		//
		// Keep the provider codec registered for future use / compatibility.
		IWorldMapProvider.CODEC.register(SafeZonesWorldMapProvider.ID, SafeZonesWorldMapProvider.class, SafeZonesWorldMapProvider.CODEC);
		// Keep the updater registered (harmless) even though we don't attach our generator globally.
		this.getChunkStoreRegistry().registerSystem(new SafeZonesWorldMapUpdateTickingSystem(this));
		this.getEventRegistry().registerGlobal(AddWorldEvent.class, event -> {
			World world = event.getWorld();
			attachWorldMapProvider(world);
			applyBenchRadiusOverride(world);
		});
		// If a player with /safezone show enabled disconnects without toggling it off,
		// ensure we drop their state so the global map overlay is only active while at least
		// one staff member currently has show enabled.
		this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
			try {
				UUID uuid = event.getPlayerRef() != null ? event.getPlayerRef().getUuid() : null;
				if (uuid != null) {
					showSettingsByPlayer.remove(uuid);
					recomputeGlobalShowState();
				}
			} catch (Throwable ignored) {
			}
		});
		// If the plugin is loaded after worlds already exist, AddWorldEvent won't fire for them.
		// Attach immediately.
		for (World world : Universe.get().getWorlds().values()) {
			attachWorldMapProvider(world);
			applyBenchRadiusOverride(world);
		}

		// Protection hooks
		SafeZonesProtectionSystems.register(this);

		// Show a title when entering a zone
		SafeZonesEnterTitleSystems.register(this);

		// Staff-only borders (/safezone show)
		SafeZonesShowBordersSystems.register(this);

		// Staff-only bench radius debug (/safezone show bench)
		SafeZonesShowBenchSystems.register(this);

		// Movement speed adjustments (e.g. faster on STREET)
		SafeZonesStreetSpeedSystems.register(this);

		// Block portal/teleporter usage & configuration based on SafeZones rules.
		// (These blocks often use SimpleBlockInteraction and bypass UseBlockEvent.)
		this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, this::onPlayerInteract);
	}

	/**
	 * Apply an optional crafting-bench container-search radius override from {@link SafeZonesConfig#benchRadius()}
	 * to the given world by mutating the loaded {@link CraftingConfig}.
	 *
	 * <p>We intentionally do this at runtime (reflection) so it can be configured via config.json without patching game files.</p>
	 */
	private void applyBenchRadiusOverride(World world) {
		try {
			if (world == null) return;
			SafeZonesConfig cfg = this.config;
			if (cfg == null) return;
			SafeZonesConfig.BenchRadiusConfig br = cfg.benchRadius();
			if (br == null) return;

			var gc = world.getGameplayConfig();
			if (gc == null) return;
			CraftingConfig cc = gc.getCraftingConfig();
			if (cc == null) return;

			// BenchRadiusConfig is normalized/clamped by SafeZonesConfig.
			Integer h = br.horizontal();
			Integer v = br.vertical();
			Integer l = br.limit();
			if (h != null) setInt(cc, "benchMaterialHorizontalChestSearchRadius", h.intValue());
			if (v != null) setInt(cc, "benchMaterialVerticalChestSearchRadius", v.intValue());
			if (l != null) setInt(cc, "benchMaterialChestLimit", l.intValue());
		} catch (Throwable ignored) {
			// Don't block plugin startup if Hytale internals change.
		}
	}

	private static void setInt(Object target, String fieldName, int value) throws Exception {
		Field f = target.getClass().getDeclaredField(fieldName);
		f.setAccessible(true);
		f.setInt(target, value);
	}

	@Override
	protected void shutdown() {
		this.engine = null;
		this.commands = null;
		this.repository = null;
		this.config = null;
		snapshotRef.set(new SafeZonesSnapshot(java.util.List.of(), java.util.Map.of()));
		SafeZonesWorldMapState.clear();
		showSettingsByPlayer.clear();
		previousWorldMapGeneratorsByWorld.clear();
		globalShowWorldMapEnabled.set(false);
		synchronized (dirtyWorldMapLock) {
			dirtyWorldMapChunks.clear();
		}
	}

	public boolean canUseShow(PermissionHolder sender) {
		if (sender == null) {
			return false;
		}
		try {
			return sender.hasPermission(PERM_ROLE_ADMIN)
					|| sender.hasPermission(PERM_ROLE_MOD)
					|| sender.hasPermission(PERM_ROLE_COMMUNITY_BUILDER);
		} catch (Exception ignored) {
			return false;
		}
	}

	public boolean isShowEnabledFor(UUID playerUuid) {
		ShowSettings settings = playerUuid != null ? showSettingsByPlayer.get(playerUuid) : null;
		return settings != null && settings.enabled();
	}

	public boolean isShowHouseEnabledFor(UUID playerUuid) {
		ShowSettings settings = playerUuid != null ? showSettingsByPlayer.get(playerUuid) : null;
		return settings != null && settings.enabled() && settings.showHouse();
	}

	public boolean isShowBorderEnabledFor(UUID playerUuid) {
		ShowSettings settings = playerUuid != null ? showSettingsByPlayer.get(playerUuid) : null;
		return settings != null && settings.enabled() && settings.showBorder();
	}

	public boolean isShowBenchEnabledFor(UUID playerUuid) {
		ShowSettings settings = playerUuid != null ? showSettingsByPlayer.get(playerUuid) : null;
		return settings != null && settings.enabled() && settings.showBench();
	}

	public ShowSettings getShowSettings(UUID playerUuid) {
		return playerUuid != null ? showSettingsByPlayer.get(playerUuid) : null;
	}

	public GridMode getShowGridMode(UUID playerUuid) {
		ShowSettings settings = playerUuid != null ? showSettingsByPlayer.get(playerUuid) : null;
		return settings != null ? settings.gridMode() : GridMode.OFF;
	}

	public boolean isShowGridEnabledFor(UUID playerUuid) {
		ShowSettings settings = playerUuid != null ? showSettingsByPlayer.get(playerUuid) : null;
		return settings != null && settings.enabled() && settings.gridMode().isEnabled();
	}

	public boolean toggleShowFor(UUID playerUuid) {
		if (playerUuid == null) {
			return false;
		}
		ShowSettings settings = showSettingsByPlayer.computeIfAbsent(playerUuid, id -> ShowSettings.defaults());
		boolean nowEnabled = !settings.enabled();
		settings.setEnabled(nowEnabled);
		if (!nowEnabled) {
			clearDebugShapesFor(playerUuid);
		}
		// IMPORTANT: Do NOT swap the world map generator on-demand.
		// Swapping generators forces the map to regenerate many chunks at once and can stall the server
		// for multiple seconds. We instead keep our generator wrapper installed (once) and only toggle a
		// boolean flag; map chunks are invalidated in small batches by SafeZonesWorldMapUpdateTickingSystem.
		recomputeGlobalShowState();
		if (nowEnabled && settings.gridMode().isEnabled()) {
			queueDirtyAroundPlayerMapView(playerUuid);
		}
		return nowEnabled;
	}

	public ShowSettings toggleShowSetting(UUID playerUuid, ShowSetting setting) {
		if (playerUuid == null || setting == null) {
			return null;
		}
		ShowSettings settings = showSettingsByPlayer.computeIfAbsent(playerUuid, id -> ShowSettings.defaults());
		boolean gridWasEnabled = settings.gridMode().isEnabled();
		if (setting == ShowSetting.HOUSE) {
			settings.setShowHouse(!settings.showHouse());
		} else if (setting == ShowSetting.GRID) {
			settings.setGridMode(settings.gridMode().next());
		} else if (setting == ShowSetting.BORDER) {
			settings.setShowBorder(!settings.showBorder());
		} else if (setting == ShowSetting.BENCH) {
			settings.setShowBench(!settings.showBench());
		}
		boolean anyVisible = settings.showHouse() || settings.gridMode().isEnabled() || settings.showBorder() || settings.showBench();
		settings.setEnabled(anyVisible);
		if (!settings.gridMode().isEnabled() && gridWasEnabled) {
			clearDebugShapesFor(playerUuid);
		}
		if (setting == ShowSetting.BORDER && !settings.showBorder()) {
			clearDebugShapesFor(playerUuid);
		}
		if (setting == ShowSetting.BENCH && !settings.showBench()) {
			clearDebugShapesFor(playerUuid);
		}
		recomputeGlobalShowState();
		if (settings.enabled() && settings.gridMode().isEnabled() && !gridWasEnabled) {
			queueDirtyAroundPlayerMapView(playerUuid);
		}
		return settings;
	}

	private void clearDebugShapesFor(UUID playerUuid) {
		try {
			com.hypixel.hytale.server.core.universe.PlayerRef pr = Universe.get().getPlayer(playerUuid);
			if (pr != null) {
				pr.getPacketHandler().writeNoCache(new ClearDebugShapes());
			}
		} catch (Exception ignored) {
		}
	}

	private void recomputeGlobalShowState() {
		GridMode nextMode = GridMode.OFF;
		for (ShowSettings s : showSettingsByPlayer.values()) {
			if (s == null || !s.enabled() || !s.gridMode().isEnabled()) {
				continue;
			}
			if (s.gridMode() == GridMode.FILLED) {
				nextMode = GridMode.FILLED;
				break;
			}
			if (s.gridMode() == GridMode.DETAILED) {
				nextMode = GridMode.DETAILED;
				// keep scanning in case someone has FILLED
				continue;
			}
			// otherwise SIMPLE
			if (nextMode == GridMode.OFF) {
				nextMode = GridMode.SIMPLE;
			}
		}

		GridMode previousMode = globalShowGridMode.getAndSet(nextMode);
		boolean nextEnabled = nextMode.isEnabled();
		boolean previousEnabled = globalShowWorldMapEnabled.getAndSet(nextEnabled);

		if (previousEnabled != nextEnabled) {
			applyGlobalWorldMapShowMode(nextEnabled);
		}
		if (!nextEnabled) {
			synchronized (dirtyWorldMapLock) {
				dirtyWorldMapChunks.clear();
			}
		} else if (previousMode != nextMode) {
			refreshWorldMapAll();
			for (Map.Entry<UUID, ShowSettings> entry : showSettingsByPlayer.entrySet()) {
				ShowSettings s = entry.getValue();
				if (s != null && s.enabled() && s.gridMode().isEnabled()) {
					queueDirtyAroundPlayerMapView(entry.getKey());
				}
			}
		}
	}

	private void applyGlobalWorldMapShowMode(boolean enabled) {
		for (World world : Universe.get().getWorlds().values()) {
			if (world == null || world.getWorldConfig().isDeleteOnRemove()) {
				continue;
			}
			world.execute(() -> {
				try {
					if (enabled) {
						IWorldMap current = world.getWorldMapManager().getGenerator();
						if (!(current instanceof SafeZonesChunkWorldMap)) {
							IWorldMap base = current;
							if (base instanceof SafeZonesBorderOverlayWorldMap) {
								base = null; // don't keep old wrapper as "previous"
							}
							if (base == null) {
								IWorldMapProvider provider = world.getWorldConfig().getWorldMapProvider();
								if (provider != null) {
									base = provider.getGenerator(world);
								}
							}
							if (base != null) {
								previousWorldMapGeneratorsByWorld.putIfAbsent(world.getName(), base);
							}
							world.getWorldMapManager().setGenerator(SafeZonesChunkWorldMap.INSTANCE);
						}
					} else {
						IWorldMap previous = previousWorldMapGeneratorsByWorld.get(world.getName());
						if (previous == null || previous instanceof SafeZonesBorderOverlayWorldMap) {
							IWorldMapProvider provider = world.getWorldConfig().getWorldMapProvider();
							if (provider != null) {
								previous = provider.getGenerator(world);
							}
						}
						if (previous != null) {
							// Wrap the base generator to avoid crashes if the world's chunk generator isn't ready yet.
							// The wrapper swallows POI generation exceptions and is a no-op overlay while /safezone show is OFF.
							world.getWorldMapManager().setGenerator(new SafeZonesBorderOverlayWorldMap(previous));
						}
					}
					refreshWorldMap(world);
				} catch (Throwable t) {
					// During auto-restart / shutdown, the world map generator can be in a partially torn-down state
					// (e.g. ChunkGeneratorResource#chunkGenerator == null), causing Hytale internals to throw.
					// This is harmless on shutdown and would otherwise spam warnings.
					if (!isLikelyWorldShutdown(t)) {
						getLogger().at(Level.WARNING).withCause(t).log("Failed to apply SafeZones world map generator");
					}
				}
			});
		}
	}

	private static boolean isLikelyWorldShutdown(Throwable t) {
		Throwable cur = t;
		while (cur != null) {
			StackTraceElement[] st = cur.getStackTrace();
			if (st != null) {
				for (StackTraceElement e : st) {
					if (e == null) continue;
					// The stack traces we see during auto-restart typically include:
					// com.hypixel.hytale.server.core.universe.world.World.onShutdown(...)
					if ("onShutdown".equals(e.getMethodName())
							&& e.getClassName() != null
							&& e.getClassName().equals("com.hypixel.hytale.server.core.universe.world.World")) {
						return true;
					}
				}
			}
			cur = cur.getCause();
		}
		return false;
	}

	private void refreshWorldMapAll() {
		for (World world : Universe.get().getWorlds().values()) {
			if (world == null || world.getWorldConfig().isDeleteOnRemove()) {
				continue;
			}
			world.execute(() -> refreshWorldMap(world));
		}
	}

	private void refreshWorldMap(World world) {
		try {
			world.getWorldMapManager().clearImages();
			for (Player player : world.getPlayers()) {
				if (player != null) {
					player.getWorldMapTracker().clear();
				}
			}
		} catch (Throwable t) {
			getLogger().at(Level.WARNING).withCause(t).log("Failed to refresh world map");
		}
	}

	public enum ShowSetting {
		HOUSE,
		GRID,
		BORDER,
		BENCH
	}

	public enum GridMode {
		OFF,
		SIMPLE,
		DETAILED,
		FILLED;

		public GridMode next() {
			return switch (this) {
				case OFF -> SIMPLE;
				case SIMPLE -> DETAILED;
				case DETAILED -> FILLED;
				case FILLED -> OFF;
			};
		}

		public boolean isEnabled() {
			return this != OFF;
		}
	}

	public static final class ShowSettings {
		private boolean enabled;
		private boolean showHouse;
		private boolean showBorder;
		private boolean showBench;
		private GridMode gridMode;

		public static ShowSettings defaults() {
			ShowSettings settings = new ShowSettings();
			settings.enabled = false;
			settings.showHouse = true;
			settings.showBorder = true;
			settings.showBench = false;
			settings.gridMode = GridMode.DETAILED;
			return settings;
		}

		public boolean enabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean showHouse() {
			return showHouse;
		}

		public void setShowHouse(boolean showHouse) {
			this.showHouse = showHouse;
		}

		public boolean showBorder() {
			return showBorder;
		}

		public void setShowBorder(boolean showBorder) {
			this.showBorder = showBorder;
		}

		public boolean showBench() {
			return showBench;
		}

		public void setShowBench(boolean showBench) {
			this.showBench = showBench;
		}

		public GridMode gridMode() {
			return gridMode;
		}

		public void setGridMode(GridMode gridMode) {
			this.gridMode = gridMode != null ? gridMode : GridMode.OFF;
		}
	}

	private void queueDirtyAroundPlayerMapView(UUID playerUuid) {
		try {
			com.hypixel.hytale.server.core.universe.PlayerRef pr = Universe.get().getPlayer(playerUuid);
			if (pr == null) {
				return;
			}
			Ref<EntityStore> ref = pr.getReference();
			if (ref == null || !ref.isValid()) {
				return;
			}
			Store<EntityStore> store = ref.getStore();
			com.hypixel.hytale.server.core.entity.entities.Player player = store.getComponent(ref, com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
			com.hypixel.hytale.server.core.modules.entity.component.TransformComponent transform = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
			if (player == null || transform == null) {
				return;
			}
			// View radius is in 32x32 chunks. We add a small buffer so the overlay appears a bit beyond the current view.
			int radius = Math.max(4, player.getViewRadius() + 2);
			long idx = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(transform.getPosition().getX(), transform.getPosition().getZ());
			int chunkX = com.hypixel.hytale.math.util.ChunkUtil.xOfChunkIndex(idx);
			int chunkZ = com.hypixel.hytale.math.util.ChunkUtil.zOfChunkIndex(idx);
			synchronized (dirtyWorldMapLock) {
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						dirtyWorldMapChunks.add(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(chunkX + dx, chunkZ + dz));
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public SafeZonesConfig getConfig() {
		return config;
	}

	public void saveConfig(SafeZonesConfig updated) throws IOException {
		if (updated == null) {
			throw new IllegalArgumentException("updated config must not be null");
		}
		Path path = this.configPath != null ? this.configPath : getDataDirectory().resolve(CONFIG_FILENAME);
		Path zp = this.zonesPath != null ? this.zonesPath : getDataDirectory().resolve(ZONES_FILENAME);
		SafeZonesConfigLoader.save(path, updated);
		this.config = updated;
		this.repository = new SafeZonesRepository(updated, zp);
		this.commands = new SafeZoneCommands(updated, repository, selectionStore);
	}

	public SafeZonesSnapshot getSnapshot() {
		return snapshotRef.get();
	}

	public ZoneEngine getEngine() {
		return engine;
	}

	public void reloadSnapshotSafely() {
		SafeZonesRepository repo = this.repository;
		if (repo == null) {
			snapshotRef.set(new SafeZonesSnapshot(java.util.List.of(), java.util.Map.of()));
			return;
		}
		try {
			SafeZonesSnapshot before = snapshotRef.get();
			SafeZonesSnapshot after = repo.loadSnapshot();
			snapshotRef.set(after);

			// Only queue map invalidations while show-mode is active.
			if (globalShowWorldMapEnabled.get()) {
				queueWorldMapInvalidation(before, after);
			}
		} catch (Exception e) {
			getLogger().at(Level.WARNING).withCause(e).log("Failed to reload SafeZones snapshot; keeping previous snapshot");
		}
	}

	/**
	 * Drain and clear all queued map-chunk indices that must be regenerated.
	 *
	 * <p>Only meaningful while global show-mode is enabled.</p>
	 */
	public LongSet drainDirtyWorldMapChunks() {
		if (!globalShowWorldMapEnabled.get()) {
			synchronized (dirtyWorldMapLock) {
				dirtyWorldMapChunks.clear();
			}
			return null;
		}
		synchronized (dirtyWorldMapLock) {
			if (dirtyWorldMapChunks.isEmpty()) {
				return null;
			}
			LongOpenHashSet copy = new LongOpenHashSet(dirtyWorldMapChunks);
			dirtyWorldMapChunks.clear();
			return copy;
		}
	}

	private void queueWorldMapInvalidation(SafeZonesSnapshot before, SafeZonesSnapshot after) {
		if (before == null) {
			before = new SafeZonesSnapshot(java.util.List.of(), java.util.Map.of());
		}
		if (after == null) {
			return;
		}

		// 1) Per-cell changes (type/claim/trust etc.) → invalidate 3x3 map-chunks around the affected cell.
		Map<ChunkPos, ChunkOverride> oldOv = before.chunkOverrides();
		Map<ChunkPos, ChunkOverride> newOv = after.chunkOverrides();
		for (Map.Entry<ChunkPos, ChunkOverride> e : newOv.entrySet()) {
			ChunkPos cell = e.getKey();
			ChunkOverride old = oldOv.get(cell);
			if (!Objects.equals(old, e.getValue())) {
				queueDirtyAroundCell(cell);
			}
		}
		for (ChunkPos cell : oldOv.keySet()) {
			if (!newOv.containsKey(cell)) {
				queueDirtyAroundCell(cell);
			}
		}

		// 2) Zone rect changes (create/edit/remove/rename) → invalidate overlapping map-chunks + 1 ring.
		Map<String, ZoneRect> oldRects = new HashMap<>();
		for (SafeZone z : before.zones()) {
			oldRects.put(z.id().toLowerCase(Locale.ROOT), z.rect());
		}
		Map<String, ZoneRect> newRects = new HashMap<>();
		for (SafeZone z : after.zones()) {
			newRects.put(z.id().toLowerCase(Locale.ROOT), z.rect());
		}
		for (String key : oldRects.keySet()) {
			ZoneRect a = oldRects.get(key);
			ZoneRect b = newRects.get(key);
			if (!Objects.equals(a, b)) {
				if (a != null) queueDirtyAroundRect(a);
				if (b != null) queueDirtyAroundRect(b);
			}
		}
		for (String key : newRects.keySet()) {
			if (oldRects.containsKey(key)) {
				continue;
			}
			ZoneRect b = newRects.get(key);
			if (b != null) queueDirtyAroundRect(b);
		}
	}

	private void queueDirtyAroundCell(ChunkPos cell) {
		if (cell == null) {
			return;
		}
		int mapChunkX = Math.floorDiv(cell.x(), 2);
		int mapChunkZ = Math.floorDiv(cell.z(), 2);
		synchronized (dirtyWorldMapLock) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					dirtyWorldMapChunks.add(ChunkUtil.indexChunk(mapChunkX + dx, mapChunkZ + dz));
				}
			}
		}
	}

	private void queueDirtyAroundRect(ZoneRect rect) {
		if (rect == null) {
			return;
		}
		int minMapX = Math.floorDiv(rect.minX(), 2) - 1;
		int maxMapX = Math.floorDiv(rect.maxX(), 2) + 1;
		int minMapZ = Math.floorDiv(rect.minZ(), 2) - 1;
		int maxMapZ = Math.floorDiv(rect.maxZ(), 2) + 1;
		synchronized (dirtyWorldMapLock) {
			for (int mx = minMapX; mx <= maxMapX; mx++) {
				for (int mz = minMapZ; mz <= maxMapZ; mz++) {
					dirtyWorldMapChunks.add(ChunkUtil.indexChunk(mx, mz));
				}
			}
		}
	}

	private void attachWorldMapProvider(World world) {
		if (world == null) {
			return;
		}
		if (world.getWorldConfig().isDeleteOnRemove()) {
			return;
		}
		// Ensure the world map provider is ours; do it on the world thread to be safe.
		world.execute(() -> {
			try {
				// Per-player /safezone show markers
				if (!world.getWorldMapManager().getMarkerProviders().containsKey(SafeZonesShowMarkerProvider.KEY)) {
					world.getWorldMapManager().addMarkerProvider(SafeZonesShowMarkerProvider.KEY, new SafeZonesShowMarkerProvider(this));
				}
				// Map generator is swapped only when /safezone show grid is enabled.
			} catch (Throwable t) {
				getLogger().at(Level.WARNING).withCause(t).log("Failed to attach SafeZones world map provider");
			}
		});
	}

	private void ensureWorldMapWrapped(World world) throws WorldMapLoadException {
		if (world == null) {
			return;
		}
		IWorldMap current = world.getWorldMapManager().getGenerator();
		if (current instanceof SafeZonesBorderOverlayWorldMap) {
			return; // already wrapped
		}
		IWorldMapProvider provider = world.getWorldConfig().getWorldMapProvider();
		IWorldMap base = current;
		if (base == null && provider != null) {
			base = provider.getGenerator(world);
		}
		if (base == null) {
			return;
		}
		// Remember the base generator so we don't lose other generators.
		previousWorldMapGeneratorsByWorld.putIfAbsent(world.getName(), base);
		world.getWorldMapManager().setGenerator(new SafeZonesBorderOverlayWorldMap(base));
	}

	public Decision decidePlayerAction(Player player, ZoneActionType type, ChunkPos chunk, Integer y) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(chunk, "chunk");

		ZoneEngine e = this.engine;
		SafeZonesConfig cfg = this.config;
		if (e == null || cfg == null) {
			return Decision.allow();
		}

		String username = player != null ? player.getDisplayName() : null;
		if (username == null || username.isBlank()) {
			return Decision.allow();
		}
		String actorLower = PlayerNames.normalize(username);
		Role role = roleForPlayer(player, cfg, actorLower);
		ZoneActionRequest request = ZoneActionRequest.playerAction(type, actorLower, role, chunk, y);
		return e.decide(getSnapshot(), request);
	}

	/**
	 * True if the chunk is a player-owned claim and the player is the owner or trusted with allowInteract.
	 * Used so harvest-like block use (e.g. sickle on crops) is allowed for owner/trusted even when BLOCK_BREAK would deny.
	 */
	public boolean wouldOwnerOrTrustedAllowInteract(Player player, ChunkPos chunk) {
		ZoneEngine e = this.engine;
		SafeZonesSnapshot snapshot = getSnapshot();
		if (e == null || snapshot == null) {
			return false;
		}
		String username = player != null ? player.getDisplayName() : null;
		if (username == null || username.isBlank()) {
			return false;
		}
		String actorLower = PlayerNames.normalize(username);
		return e.wouldOwnerOrTrustedAllowInteractInClaim(snapshot, chunk, actorLower);
	}

	/**
	 * Bench crafting is a special case: Hytale benches can consume materials from nearby containers.
	 * For security, we want the same rule as "Owner/Trusted" regardless of staff role, otherwise
	 * moderators/admins could unintentionally consume from player-owned containers without explicit trust.
	 */
	public Decision decideBenchContainerAccess(Player player, ChunkPos chunk) {
		Objects.requireNonNull(chunk, "chunk");

		ZoneEngine e = this.engine;
		SafeZonesConfig cfg = this.config;
		if (e == null || cfg == null) {
			return Decision.allow();
		}
		String username = player != null ? player.getDisplayName() : null;
		if (username == null || username.isBlank()) {
			return Decision.allow();
		}
		String actorLower = PlayerNames.normalize(username);
		// Force Role.PLAYER to avoid staff bypass for bench-material container access.
		ZoneActionRequest request = ZoneActionRequest.playerAction(ZoneActionType.CONTAINER_OPEN, actorLower, Role.PLAYER, chunk, null);
		return e.decide(getSnapshot(), request);
	}

	public Locale localeForPlayer(Player player) {
		if (player == null) {
			return Locale.ENGLISH;
		}
		try {
			if (player.getReference() == null || !player.getReference().isValid()) {
				return Locale.getDefault();
			}
			com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref = player.getReference();
			com.hypixel.hytale.server.core.universe.PlayerRef pr = ref.getStore().getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
			if (pr == null) {
				return Locale.getDefault();
			}
			return localeForLanguageTag(pr.getLanguage());
		} catch (Exception ignored) {
			return Locale.getDefault();
		}
	}

	public Locale localeForLanguageTag(String languageTag) {
		if (languageTag == null || languageTag.isBlank()) {
			return Locale.ENGLISH;
		}
		// Hytale uses strings like "en_us".
		String normalized = languageTag.replace('_', '-');
		Locale locale = Locale.forLanguageTag(normalized);
		return (locale == null || locale.getLanguage() == null || locale.getLanguage().isBlank()) ? Locale.ENGLISH : locale;
	}

	public void denyWithMessage(Player player, Decision decision, String languageTagOrNull) {
		if (player == null || decision == null || decision.allowed()) {
			return;
		}
		SafeZonesConfig cfg = this.config;
		if (cfg == null) {
			return;
		}

		Locale locale = localeForLanguageTag(languageTagOrNull);

		String specific = cfg.message(locale, decision.reasonKey());
		String msg;
		if (specific != null && !specific.isBlank() && !specific.equals(decision.reasonKey())) {
			msg = specific;
		} else {
			msg = cfg.message(locale, "actionDenied");
		}
		player.sendMessage(Message.raw(msg).color(Color.RED));
	}

	private void onPlayerInteract(PlayerInteractEvent event) {
		try {
			if (event == null || event.isCancelled()) {
				return;
			}
			Player player = event.getPlayer();
			if (player == null) {
				return;
			}
			var world = player.getWorld();
			if (world == null) {
				return;
			}

			Vector3i targetBlock = event.getTargetBlock();

			// Only relevant for portal/teleporter blocks.
			if (targetBlock == null) {
				return;
			}
			BlockType bt = world.getBlockType(targetBlock);
			if (bt == null) {
				return;
			}
			String id = bt.getId();
			if (id == null) {
				return;
			}
			String idLower = id.toLowerCase(Locale.ROOT);
			if (!idLower.contains("portal") && !idLower.contains("teleporter")) {
				return;
			}

			SafeZonesSnapshot snapshot = getSnapshot();
			ChunkPos cell = SafeZonesProtectionSystems.chunkFromBlock(targetBlock);
			if (snapshot == null || snapshot.findZoneFor(cell).isEmpty()) {
				return;
			}

			// If this cell is a HOUSE (H), then:
			// - only staff may open/configure the UI
			// - nobody may actually teleport/enter via these blocks (enforced by Teleport intercept)
			boolean isHouse = snapshot.chunkOverrideOrNone(cell).type() == de.hytalede.safezones.core.CellType.HOUSE;
			if (isHouse) {
				if (!canEditPortalUi(player)) {
					event.setCancelled(true);
					denyWithMessage(player, Decision.deny(ZoneEngine.REASON_DENIED_INTERACT), languageOf(event));
					return;
				}
				// Allow UI for staff, but mark so any resulting teleport is blocked.
				PlayerRef pr = playerRefOf(event);
				if (pr != null && pr.getUuid() != null) {
					SafeZonesProtectionSystems.markPortalOrTeleporterInteract(pr.getUuid());
				}
				return;
			}

			// Non-house SafeZone:
			// - allow staff/OP to open/configure portal/teleporter UIs even if allowInteract=false
			// - otherwise, follow the normal INTERACT rules for this zone/cell
			if (canEditPortalUi(player)) {
				return;
			}
			Decision d = decidePlayerAction(player, ZoneActionType.INTERACT, cell, null);
			if (!d.allowed()) {
				event.setCancelled(true);
				denyWithMessage(player, d, languageOf(event));
				return;
			}
		} catch (Throwable ignored) {
		}
	}

	private boolean canEditPortalUi(Player player) {
		if (player == null) {
			return false;
		}
		// Staff roles OR OP (permissions group with wildcard).
		if (canUseShow(player)) {
			return true;
		}
		try {
			return player.hasPermission("*");
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static PlayerRef playerRefOf(PlayerInteractEvent event) {
		try {
			var ref = event != null ? event.getPlayerRef() : null;
			if (ref == null || !ref.isValid()) {
				return null;
			}
			return ref.getStore().getComponent(ref, PlayerRef.getComponentType());
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String languageOf(PlayerInteractEvent event) {
		try {
			PlayerRef pr = playerRefOf(event);
			return pr != null ? pr.getLanguage() : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	public void executeSafeZoneCommand(com.hypixel.hytale.server.core.command.system.CommandContext context) {
		SafeZonesConfig cfg = this.config;
		SafeZoneCommands c = this.commands;
		SafeZonesRepository repo = this.repository;
		if (cfg == null || c == null || repo == null) {
			context.sendMessage(Message.raw("SafeZones plugin is not initialized yet.").color(Color.RED));
			return;
		}

		if (!context.isPlayer()) {
			context.sendMessage(Message.raw("This command can only be used by players.").color(Color.RED));
			return;
		}

		Player sender = context.senderAs(Player.class);
		if (sender.getWorld() == null) {
			context.sendMessage(Message.raw("No world available.").color(Color.RED));
			return;
		}

		// Ensure all entity/world access happens on the world thread.
		sender.getWorld().execute(() -> {
			try {
				String input = context.getInputString();
				String[] tokens = input == null ? new String[0] : input.trim().split("\\s+");
				String[] args = tokens.length <= 1 ? new String[0] : Arrays.copyOfRange(tokens, 1, tokens.length);

				ChunkPos chunk = SafeZonesProtectionSystems.chunkFromPlayer(sender);
				String senderLower = PlayerNames.normalize(sender.getDisplayName());
				Role role = roleForPlayer(sender, cfg, senderLower);

				Locale locale = localeForPlayer(sender);
				// Use the sender's current block Y as reference for lot ground when claiming.
				int y = 0;
				try {
					var ref = sender.getReference();
					if (ref != null && ref.isValid()) {
						var transform = ref.getStore().getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
						if (transform != null) {
							y = (int) Math.floor(transform.getPosition().getY());
						}
					}
				} catch (Exception ignored) {
				}
				CommandContext coreCtx = new CommandContext(senderLower, role, chunk, y, locale);

				CommandResponse response = c.execute(args, coreCtx);
				context.sendMessage(Message.raw(response.message()).color(response.ok() ? Color.GREEN : Color.RED));

				// Commands can persist changes; refresh cache.
				reloadSnapshotSafely();

				// If the player updated the zone title, refresh the EventTitle immediately (no need to leave/re-enter).
				if (args.length > 0 && "title".equalsIgnoreCase(args[0])) {
					try {
						var pr = Universe.get().getPlayer(context.sender().getUuid());
						if (pr != null) {
							SafeZone zoneNow = getSnapshot().findZoneFor(chunk).orElse(null);
							if (zoneNow != null) {
								var tt = cfg != null ? cfg.eventTitle() : null;
								float duration = tt != null ? tt.duration() : 99999.0f;
								float fadeIn = tt != null ? tt.fadeIn() : 0.2f;
								float fadeOut = tt != null ? tt.fadeOut() : 0.2f;
								EventTitleUtil.hideEventTitleFromPlayer(pr, 0.0f);
								EventTitleUtil.showEventTitleToPlayer(
										pr,
										Message.raw(zoneNow.titleOrDefault("SafeZone")),
										Message.raw(zoneNow.id()),
										false,
										null,
										duration,
										fadeIn,
										fadeOut
								);
							}
						}
					} catch (Throwable ignored) {
					}
				}
			} catch (IOException e) {
				getLogger().at(Level.WARNING).withCause(e).log("SafeZones command failed (IO)");
				context.sendMessage(Message.raw("Command failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
						.color(Color.RED));
			} catch (Exception e) {
				getLogger().at(Level.WARNING).withCause(e).log("SafeZones command failed");
				context.sendMessage(Message.raw("Command failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
						.color(Color.RED));
			}
		});
	}

	private void ensureDefaultResource(Path targetPath, String resourcePath) {
		try {
			if (Files.exists(targetPath)) {
				return;
			}
			Files.createDirectories(targetPath.getParent());
			try (InputStream in = SafeZonesHytalePlugin.class.getResourceAsStream(resourcePath)) {
				if (in == null) {
					getLogger().at(Level.WARNING).log("Default resource missing: %s", resourcePath);
					return;
				}
				Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
			}
			getLogger().at(Level.INFO).log("Created default file: %s", targetPath.toAbsolutePath().toString());
		} catch (IOException e) {
			getLogger().at(Level.WARNING).withCause(e).log("Failed to create default file at %s", targetPath.toAbsolutePath().toString());
		}
	}

	private static Role roleForPlayer(Player player, SafeZonesConfig cfg, String playerNameLower) {
		try {
			if (player != null) {
				// Prefer Hytale permission system (groups/permissions.json).
				if (player.hasPermission(PERM_ROLE_ADMIN)) {
					return Role.ADMIN;
				}
				if (player.hasPermission(PERM_ROLE_MOD)) {
					return Role.MOD;
				}
				if (player.hasPermission(PERM_ROLE_COMMUNITY_BUILDER)) {
					return Role.COMMUNITY_BUILDER;
				}
			}
		} catch (Exception ignored) {
			// fall back to config
		}
		return cfg != null ? cfg.roles().roleOf(playerNameLower) : Role.PLAYER;
	}
}

