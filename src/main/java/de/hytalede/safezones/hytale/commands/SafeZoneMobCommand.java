package de.hytalede.safezones.hytale.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * /safezone mob list
 * /safezone mob allow <NPC_TYPE_ID>
 * /safezone mob block <NPC_TYPE_ID>
 *
 * <p>Manages the global mob-enter whitelist (NPC type ids) used by SafeZones.</p>
 */
public final class SafeZoneMobCommand extends CommandBase {
	private final SafeZonesHytalePlugin plugin;

	public SafeZoneMobCommand(SafeZonesHytalePlugin plugin, String name, String descriptionKey, String... aliases) {
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
			context.sendMessage(Message.raw("Usage: /safezone mob list  OR  /safezone mob allow <NPC_TYPE_ID>  OR  /safezone mob block <NPC_TYPE_ID>"));
			return;
		}

		SafeZonesConfig cfg = plugin.getConfig();
		if (cfg == null) {
			context.sendMessage(Message.raw("SafeZones config not loaded."));
			return;
		}

		if (parsed.mode == Mode.LIST) {
			List<String> ids = new ArrayList<>(cfg.mobEnterWhitelistNpcTypeIds());
			ids.sort(String.CASE_INSENSITIVE_ORDER);
			if (ids.isEmpty()) {
				context.sendMessage(Message.raw("Mob enter whitelist is empty."));
				return;
			}
			context.sendMessage(Message.raw("Mob enter whitelist (" + ids.size() + "): " + String.join(", ", ids)));
			return;
		}

		Set<String> next = new LinkedHashSet<>(cfg.mobEnterWhitelistNpcTypeIds());
		String id = parsed.npcTypeId;
		boolean changed;
		if (parsed.mode == Mode.ALLOW) {
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
				cfg.houseBlockedItemIds(),
				Set.copyOf(next),
				cfg.placeDistanceByItemId(),
				cfg.benchRadius(),
				cfg.claimLimits()
		);

		try {
			plugin.saveConfig(updated);
			context.sendMessage(Message.raw((parsed.mode == Mode.ALLOW ? "Whitelisted" : "Removed from whitelist") + ": " + id + (changed ? "" : " (no change)")));
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

	private enum Mode { LIST, ALLOW, BLOCK }

	private static final class Parsed {
		private final Mode mode;
		private final String npcTypeId;

		private Parsed(Mode mode, String npcTypeId) {
			this.mode = mode;
			this.npcTypeId = npcTypeId;
		}
	}

	private static Parsed parse(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String[] parts = input.trim().split("\\s+");
		if (parts.length < 3) {
			return null;
		}
		int idx = 0;
		String first = parts[idx];
		if (first.startsWith("/")) {
			first = first.substring(1);
		}
		// Accept "/safezone mob ..." or "safezone mob ..." or "mob ..." (when invoked as subcommand)
		if (first.equalsIgnoreCase("safezone")) {
			idx++;
			if (idx >= parts.length || !parts[idx].equalsIgnoreCase("mob")) {
				return null;
			}
			idx++;
		} else if (first.equalsIgnoreCase("mob")) {
			idx++;
		} else {
			return null;
		}
		if (idx >= parts.length) {
			return null;
		}
		String modeToken = parts[idx++].trim().toLowerCase(Locale.ROOT);
		Mode mode = switch (modeToken) {
			case "list" -> Mode.LIST;
			case "allow", "add", "whitelist" -> Mode.ALLOW;
			case "block", "remove", "del", "delete", "unwhitelist" -> Mode.BLOCK;
			default -> null;
		};
		if (mode == null) {
			return null;
		}
		if (mode == Mode.LIST) {
			return new Parsed(mode, null);
		}
		if (idx >= parts.length) {
			return null;
		}
		String npcTypeId = String.join(" ", java.util.Arrays.copyOfRange(parts, idx, parts.length)).trim();
		if (npcTypeId.isBlank()) {
			return null;
		}
		npcTypeId = npcTypeId.toLowerCase(Locale.ROOT);
		return new Parsed(mode, npcTypeId);
	}
}

