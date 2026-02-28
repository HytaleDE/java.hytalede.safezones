package de.hytalede.safezones.hytale.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.gameplay.CraftingConfig;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Objects;

/**
 * /safezone benchradius <horizontal:0-7> <vertical:0-7> [limit:0-200]
 *
 * Changes the crafting bench "nearby container" search radius at runtime by mutating the loaded GameplayConfig.
 */
public final class SafeZoneBenchRadiusCommand extends CommandBase {
	private final SafeZonesHytalePlugin plugin;

	public SafeZoneBenchRadiusCommand(SafeZonesHytalePlugin plugin, String name, String descriptionKey, String... aliases) {
		super(name, descriptionKey);
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.setAllowsExtraArguments(true);
		if (aliases != null && aliases.length > 0) {
			this.addAliases(aliases);
		}
	}

	@Override
	protected void executeSync(CommandContext context) {
		CommandSender sender = context.sender();
		if (!context.isPlayer()) {
			context.sendMessage(Message.raw("This command can only be used by players."));
			return;
		}
		if (sender == null || !isAdmin(sender)) {
			context.sendMessage(Message.raw("Dazu hast du keine Berechtigung."));
			return;
		}

		Parsed parsed = parse(context.getInputString());
		if (parsed == null) {
			context.sendMessage(Message.raw("Usage: /safezone benchradius <horizontal:0-7> <vertical:0-7> [limit:0-200]"));
			return;
		}

		SafeZonesConfig cfg = plugin.getConfig();
		if (cfg == null) {
			context.sendMessage(Message.raw("SafeZones config not loaded."));
			return;
		}

		// Persist: store limit too (if omitted, keep existing configured limit or default 100).
		Integer limit = parsed.limit != null ? parsed.limit : (cfg.benchRadius() != null ? cfg.benchRadius().limit() : 100);
		SafeZonesConfig.BenchRadiusConfig benchRadius = new SafeZonesConfig.BenchRadiusConfig(parsed.horizontal, parsed.vertical, limit).normalizedOrNull();

		int changedWorlds = 0;
		for (World world : Universe.get().getWorlds().values()) {
			if (world == null) continue;
			if (apply(world, parsed.horizontal, parsed.vertical, limit)) {
				changedWorlds++;
			}
		}

		// Save to config.json (so it applies after restart).
		SafeZonesConfig updated = new SafeZonesConfig(
				cfg.roles(),
				cfg.defaults(),
				cfg.messages(),
				cfg.eventTitle(),
				cfg.waterPlacement(),
				cfg.mobEnterCheckIntervalMs(),
				cfg.streetSpeedMultiplier(),
				cfg.streetSpeedApplyDelayMs(),
				cfg.houseBlockedItemIds(),
				cfg.mobEnterWhitelistNpcTypeIds(),
				cfg.placeDistanceByItemId(),
				benchRadius,
				cfg.claimLimits()
		);
		try {
			plugin.saveConfig(updated);
		} catch (Exception ignored) {
		}

		context.sendMessage(Message.raw(
				"Workbench radius updated: horizontal=" + parsed.horizontal
						+ ", vertical=" + parsed.vertical
						+ (limit != null ? ", limit=" + limit : "")
						+ " (worlds=" + changedWorlds + ")"
		));
	}

	@Override
	protected boolean canGeneratePermission() {
		return false;
	}

	private boolean isAdmin(CommandSender sender) {
		try {
			// Admins only (same as /safezone item ...)
			return sender.hasPermission(SafeZonesHytalePlugin.PERM_ROLE_ADMIN);
		} catch (Exception ignored) {
			return false;
		}
	}

	private static boolean apply(World world, int horizontal, int vertical, Integer limit) {
		try {
			var gc = world.getGameplayConfig();
			if (gc == null) return false;
			CraftingConfig cc = gc.getCraftingConfig();
			if (cc == null) return false;

			setInt(cc, "benchMaterialHorizontalChestSearchRadius", horizontal);
			setInt(cc, "benchMaterialVerticalChestSearchRadius", vertical);
			if (limit != null) {
				setInt(cc, "benchMaterialChestLimit", limit.intValue());
			}
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static void setInt(Object target, String fieldName, int value) throws Exception {
		Field f = target.getClass().getDeclaredField(fieldName);
		f.setAccessible(true);
		f.setInt(target, value);
	}

	private static final class Parsed {
		private final int horizontal;
		private final int vertical;
		private final Integer limit;

		private Parsed(int horizontal, int vertical, Integer limit) {
			this.horizontal = horizontal;
			this.vertical = vertical;
			this.limit = limit;
		}
	}

	private static Parsed parse(String input) {
		if (input == null || input.isBlank()) return null;
		String[] parts = input.trim().split("\\s+");
		int idx = 0;
		String first = parts[idx];
		if (first.startsWith("/")) first = first.substring(1);

		// Accept "/safezone benchradius ..." or "safezone benchradius ..." or "benchradius ..." (subcommand)
		if (first.equalsIgnoreCase("safezone")) {
			idx++;
			if (idx >= parts.length) return null;
			if (!parts[idx].equalsIgnoreCase("benchradius")) return null;
			idx++;
		} else if (first.equalsIgnoreCase("benchradius")) {
			idx++;
		} else {
			return null;
		}

		if (idx + 1 >= parts.length) return null;
		Integer h = parseInt(parts[idx++]);
		Integer v = parseInt(parts[idx++]);
		if (h == null || v == null) return null;

		int horizontal = clamp(h.intValue(), 0, 7);
		int vertical = clamp(v.intValue(), 0, 7);

		Integer limit = null;
		if (idx < parts.length) {
			limit = parseInt(parts[idx]);
			if (limit != null) {
				limit = clamp(limit.intValue(), 0, 200);
			}
		}

		return new Parsed(horizontal, vertical, limit);
	}

	private static Integer parseInt(String s) {
		if (s == null) return null;
		String t = s.trim().toLowerCase(Locale.ROOT);
		if (t.isBlank()) return null;
		try {
			return Integer.parseInt(t);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static int clamp(int v, int min, int max) {
		if (v < min) return min;
		if (v > max) return max;
		return v;
	}
}

