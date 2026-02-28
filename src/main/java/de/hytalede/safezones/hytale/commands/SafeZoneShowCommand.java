package de.hytalede.safezones.hytale.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Locale;
import java.util.Objects;

/**
 * /safezone show - toggles staff-only SafeZone border visualization (in-world + map markers).
 */
public final class SafeZoneShowCommand extends CommandBase {
	private final SafeZonesHytalePlugin plugin;

	public SafeZoneShowCommand(SafeZonesHytalePlugin plugin, String name, String descriptionKey, String... aliases) {
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
		if (!plugin.canUseShow(sender)) {
			context.sendMessage(Message.raw("Dazu hast du keine Berechtigung."));
			return;
		}

		String settingToken = parseSetting(context.getInputString());
		if (settingToken == null) {
			boolean enabled = plugin.toggleShowFor(sender.getUuid());
			SafeZonesHytalePlugin.ShowSettings settings = plugin.getShowSettings(sender.getUuid());
			context.sendMessage(Message.raw(buildStatusMessage(enabled, settings)));
			return;
		}

		SafeZonesHytalePlugin.ShowSetting setting = parseSettingToken(settingToken);
		if (setting == null) {
			context.sendMessage(Message.raw("Usage: /safezone show [house|grid|border|bench]  (grid cycles: OFF -> SIMPLE -> DETAILED -> FILLED -> OFF)"));
			return;
		}
		SafeZonesHytalePlugin.ShowSettings settings = plugin.toggleShowSetting(sender.getUuid(), setting);
		boolean enabled = settings != null && settings.enabled();
		context.sendMessage(Message.raw(buildStatusMessage(enabled, settings)));
	}

	private static String parseSetting(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String[] parts = input.trim().split("\\s+");
		if (parts.length <= 1) {
			return null;
		}
		String first = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
		// Accept both "/safezone show <setting>" and "show <setting>"
		if (parts.length >= 3 && first.equalsIgnoreCase("safezone") && parts[1].equalsIgnoreCase("show")) {
			return parts[2];
		}
		if (parts.length >= 2 && first.equalsIgnoreCase("show")) {
			return parts[1];
		}
		if (parts.length >= 2 && first.equalsIgnoreCase("safezone")) {
			String second = parts[1];
			if (second.equalsIgnoreCase("show")) {
				return null;
			}
			return second;
		}
		return parts.length >= 3 ? parts[parts.length - 1] : null;
	}

	private static SafeZonesHytalePlugin.ShowSetting parseSettingToken(String token) {
		if (token == null) {
			return null;
		}
		String normalized = token.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "house" -> SafeZonesHytalePlugin.ShowSetting.HOUSE;
			case "grid" -> SafeZonesHytalePlugin.ShowSetting.GRID;
			case "border" -> SafeZonesHytalePlugin.ShowSetting.BORDER;
			case "bench" -> SafeZonesHytalePlugin.ShowSetting.BENCH;
			default -> null;
		};
	}

	private static String buildStatusMessage(boolean enabled, SafeZonesHytalePlugin.ShowSettings settings) {
		String house = settings != null && settings.showHouse() ? "ON" : "OFF";
		String border = settings != null && settings.showBorder() ? "ON" : "OFF";
		String bench = settings != null && settings.showBench() ? "ON" : "OFF";
		String grid = settings != null ? settings.gridMode().name() : "OFF";
		return "SafeZone show: " + (enabled ? "ON" : "OFF") + " (house=" + house + ", border=" + border + ", bench=" + bench + ", grid=" + grid + ")";
	}

	@Override
	protected boolean canGeneratePermission() {
		// Permission is checked via plugin.canUseShow(sender) inside executeSync.
		// Disable Hytale's auto-generated permission node so the command isn't blocked pre-execution.
		return false;
	}
}

