package de.hytalede.safezones.hytale.commands;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Objects;

/**
 * A thin Hytale command wrapper that delegates to the SafeZones core command parser.
 *
 * <p>We allow extra arguments so that the core parser can handle variants (e.g. claim with/without player).</p>
 */
public final class SafeZonePassthroughCommand extends CommandBase {
	private final SafeZonesHytalePlugin plugin;

	public SafeZonePassthroughCommand(SafeZonesHytalePlugin plugin, String name, String descriptionKey, String... aliases) {
		super(name, descriptionKey);
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.setAllowsExtraArguments(true);
		if (aliases != null && aliases.length > 0) {
			this.addAliases(aliases);
		}
	}

	@Override
	protected void executeSync(CommandContext context) {
		plugin.executeSafeZoneCommand(context);
	}

	@Override
	protected boolean canGeneratePermission() {
		// We enforce permissions/ownership inside the SafeZones core, not via Hytale's auto-generated
		// per-command permission nodes (which would block normal players from running e.g. /safezone trust).
		return false;
	}
}

