package de.hytalede.safezones.hytale.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * {@code /safezone item block <ITEM_ID>}
 * {@code /safezone item allow <ITEM_ID>}
 *
 * <p>Manages a blacklist of items that cannot be placed inside house cells (H).</p>
 */
public final class SafeZoneItemCommand extends CommandBase {
	private final SafeZonesHytalePlugin plugin;

	public SafeZoneItemCommand(SafeZonesHytalePlugin plugin, String name, String descriptionKey, String... aliases) {
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
			context.sendMessage(Message.raw("Usage: /safezone item block <ITEM_ID>  OR  /safezone item allow <ITEM_ID>"));
			return;
		}

		SafeZonesConfig cfg = plugin.getConfig();
		if (cfg == null) {
			context.sendMessage(Message.raw("SafeZones config not loaded."));
			return;
		}

		Set<String> next = new LinkedHashSet<>(cfg.houseBlockedItemIds());
		String id = parsed.itemId;
		boolean changed;
		if (parsed.mode == Mode.BLOCK) {
			changed = next.add(id);
		} else {
			changed = next.remove(id);
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
				Set.copyOf(next),
				cfg.mobEnterWhitelistNpcTypeIds(),
				cfg.placeDistanceByItemId(),
				cfg.benchRadius(),
				cfg.claimLimits()
		);

		try {
			plugin.saveConfig(updated);
			context.sendMessage(Message.raw((parsed.mode == Mode.BLOCK ? "Blocked" : "Allowed") + " item in houses: " + id + (changed ? "" : " (no change)")));
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

	private enum Mode { BLOCK, ALLOW }

	private static final class Parsed {
		private final Mode mode;
		private final String itemId;

		private Parsed(Mode mode, String itemId) {
			this.mode = mode;
			this.itemId = itemId;
		}
	}

	private static Parsed parse(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String[] parts = input.trim().split("\\s+");
		if (parts.length < 4) {
			return null;
		}
		int idx = 0;
		String first = parts[idx];
		if (first.startsWith("/")) {
			first = first.substring(1);
		}
		// Accept "/safezone item ..." or "safezone item ..." or "item ..." (when invoked as subcommand)
		if (first.equalsIgnoreCase("safezone")) {
			idx++;
			if (idx >= parts.length || !parts[idx].equalsIgnoreCase("item")) {
				return null;
			}
			idx++;
		} else if (first.equalsIgnoreCase("item")) {
			idx++;
		} else {
			return null;
		}
		if (idx >= parts.length) {
			return null;
		}
		String modeToken = parts[idx++].trim().toLowerCase(Locale.ROOT);
		Mode mode = switch (modeToken) {
			case "block" -> Mode.BLOCK;
			case "allow" -> Mode.ALLOW;
			default -> null;
		};
		if (mode == null) {
			return null;
		}
		if (idx >= parts.length) {
			return null;
		}
		String itemId = String.join(" ", java.util.Arrays.copyOfRange(parts, idx, parts.length)).trim();
		if (itemId.isBlank()) {
			return null;
		}
		itemId = itemId.toLowerCase(Locale.ROOT);
		return new Parsed(mode, itemId);
	}
}

