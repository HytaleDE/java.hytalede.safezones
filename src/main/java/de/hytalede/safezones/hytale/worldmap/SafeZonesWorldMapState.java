package de.hytalede.safezones.hytale.worldmap;

import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Static bridge so the world map generator can access the current SafeZones snapshot.
 *
 * <p>The Hytale world map provider/generator APIs don't pass plugin instances around, so we keep a
 * supplier here that is set from {@code SafeZonesHytalePlugin}.</p>
 */
public final class SafeZonesWorldMapState {
	private static volatile Supplier<SafeZonesSnapshot> snapshotSupplier;
	private static volatile Supplier<Boolean> showEnabledSupplier;
	private static volatile Supplier<SafeZonesHytalePlugin.GridMode> gridModeSupplier;

	private SafeZonesWorldMapState() {
	}

	public static void setSnapshotSupplier(Supplier<SafeZonesSnapshot> supplier) {
		snapshotSupplier = Objects.requireNonNull(supplier, "supplier");
	}

	public static void setShowEnabledSupplier(Supplier<Boolean> supplier) {
		showEnabledSupplier = Objects.requireNonNull(supplier, "supplier");
	}

	public static void setGridModeSupplier(Supplier<SafeZonesHytalePlugin.GridMode> supplier) {
		gridModeSupplier = Objects.requireNonNull(supplier, "supplier");
	}

	public static void clear() {
		snapshotSupplier = null;
		showEnabledSupplier = null;
		gridModeSupplier = null;
	}

	public static SafeZonesSnapshot snapshotOrEmpty() {
		Supplier<SafeZonesSnapshot> supplier = snapshotSupplier;
		if (supplier == null) {
			return new SafeZonesSnapshot(java.util.List.of(), java.util.Map.of());
		}
		SafeZonesSnapshot s = supplier.get();
		return s != null ? s : new SafeZonesSnapshot(java.util.List.of(), java.util.Map.of());
	}

	public static boolean isShowEnabled() {
		Supplier<Boolean> supplier = showEnabledSupplier;
		if (supplier == null) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(supplier.get());
		} catch (Exception ignored) {
			return false;
		}
	}

	public static SafeZonesHytalePlugin.GridMode getGridMode() {
		Supplier<SafeZonesHytalePlugin.GridMode> supplier = gridModeSupplier;
		if (supplier == null) {
			return SafeZonesHytalePlugin.GridMode.OFF;
		}
		try {
			SafeZonesHytalePlugin.GridMode mode = supplier.get();
			return mode != null ? mode : SafeZonesHytalePlugin.GridMode.OFF;
		} catch (Exception ignored) {
			return SafeZonesHytalePlugin.GridMode.OFF;
		}
	}
}

