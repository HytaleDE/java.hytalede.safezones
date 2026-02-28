package de.hytalede.safezones.hytale.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /safezone placeDistance <ITEM_ID> <DISTANCE_IN_BLOCKS>}
 *
 * <p>Adds/updates a placement distance rule for a specific placeable item/block.
 * Distance is clamped to [0..4]. A value of 0 removes the rule.</p>
 */
public final class SafeZonePlaceDistanceCommand extends CommandBase {
	private final SafeZonesHytalePlugin plugin;

	public SafeZonePlaceDistanceCommand(SafeZonesHytalePlugin plugin, String name, String descriptionKey, String... aliases) {
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
			context.sendMessage(Message.raw("Usage: /safezone placeDistance <ITEM_ID> <DISTANCE_IN_BLOCKS(0-4)>"));
			return;
		}

		SafeZonesConfig cfg = plugin.getConfig();
		if (cfg == null) {
			context.sendMessage(Message.raw("SafeZones config not loaded."));
			return;
		}

		Map<String, Integer> next = new LinkedHashMap<>(cfg.placeDistanceByItemId());
		boolean removed = false;
		if (parsed.distance <= 0) {
			removed = next.remove(parsed.itemId) != null;
		} else {
			next.put(parsed.itemId, parsed.distance);
		}

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
				Map.copyOf(next),
				cfg.benchRadius(),
				cfg.claimLimits()
		);

		try {
			plugin.saveConfig(updated);
			String msg = (parsed.distance <= 0)
					? ("Removed placeDistance rule for " + parsed.itemId + (removed ? "" : " (no change)"))
					: ("Set placeDistance: " + parsed.itemId + " -> " + parsed.distance);
			context.sendMessage(Message.raw(msg));
		} catch (Exception e) {
			context.sendMessage(Message.raw("Failed to save config: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
		}
	}

	@Override
	protected boolean canGeneratePermission() {
		return false;
	}

	private boolean isAdmin(CommandSender sender) {
		try {
			return sender.hasPermission(SafeZonesHytalePlugin.PERM_ROLE_ADMIN);
		} catch (Exception ignored) {
			return false;
		}
	}

	private static final class Parsed {
		private final String itemId;
		private final int distance;

		private Parsed(String itemId, int distance) {
			this.itemId = itemId;
			this.distance = distance;
		}
	}

	private static Parsed parse(String input) {
		if (input == null || input.isBlank()) return null;
		String[] parts = input.trim().split("\\s+");
		// Accept both:
		// - /safezone placeDistance <id> <dist>
		// - placeDistance <id> <dist> (subcommand tokenization)
		int idx = 0;
		String first = parts[idx];
		if (first.startsWith("/")) first = first.substring(1);
		if (first.equalsIgnoreCase("safezone")) {
			idx++;
			if (idx >= parts.length || !parts[idx].equalsIgnoreCase("placedistance")) return null;
			idx++;
		} else if (first.equalsIgnoreCase("placedistance")) {
			idx++;
		} else {
			return null;
		}
		if (idx + 1 >= parts.length) return null;
		String itemId = parts[idx++].trim().toLowerCase(Locale.ROOT);
		Integer dist = parseInt(parts[idx]);
		if (itemId.isBlank() || dist == null) return null;
		int d = clamp(dist.intValue(), 0, 4);
		return new Parsed(itemId, d);
	}

	private static Integer parseInt(String s) {
		if (s == null) return null;
		try {
			return Integer.parseInt(s.trim());
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

