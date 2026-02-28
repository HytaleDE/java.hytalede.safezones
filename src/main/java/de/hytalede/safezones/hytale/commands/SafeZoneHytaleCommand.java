package de.hytalede.safezones.hytale.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import de.hytalede.safezones.hytale.SafeZonesHytalePlugin;

import java.util.Objects;

/**
 * /safezone ...
 *
 * <p>Implemented as an {@link AbstractCommandCollection} so the client command UI can display all subcommands.</p>
 */
public final class SafeZoneHytaleCommand extends AbstractCommandCollection {
	public SafeZoneHytaleCommand(SafeZonesHytalePlugin plugin) {
		super("safezone", "hytalede.safezones.commands.safezone.desc");
		Objects.requireNonNull(plugin, "plugin");

		// Selection / zones
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "start", "hytalede.safezones.commands.safezone.start.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "end", "hytalede.safezones.commands.safezone.end.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "edit", "hytalede.safezones.commands.safezone.edit.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "name", "hytalede.safezones.commands.safezone.name.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "title", "hytalede.safezones.commands.safezone.title.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "list", "hytalede.safezones.commands.safezone.list.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "remove", "hytalede.safezones.commands.safezone.remove.desc"));
		this.addSubCommand(new SafeZoneShowCommand(plugin, "show", "hytalede.safezones.commands.safezone.show.desc"));

		// Claims / permissions
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "claim", "hytalede.safezones.commands.safezone.claim.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "unclaim", "hytalede.safezones.commands.safezone.unclaim.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "trust", "hytalede.safezones.commands.safezone.trust.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "untrust", "hytalede.safezones.commands.safezone.untrust.desc"));

		// Settings
		this.addSubCommand(new SafeZoneItemCommand(plugin, "item", "hytalede.safezones.commands.safezone.item.desc"));
		this.addSubCommand(new SafeZoneMobCommand(plugin, "mob", "hytalede.safezones.commands.safezone.mob.desc", "mobs", "mobwhitelist"));
		this.addSubCommand(new SafeZoneBenchRadiusCommand(plugin, "benchradius", "hytalede.safezones.commands.safezone.benchradius.desc"));
		this.addSubCommand(new SafeZonePlaceDistanceCommand(plugin, "placedistance", "hytalede.safezones.commands.safezone.placedistance.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "set", "hytalede.safezones.commands.safezone.set.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "flag", "hytalede.safezones.commands.safezone.flag.desc"));
		this.addSubCommand(new SafeZonePassthroughCommand(plugin, "info", "hytalede.safezones.commands.safezone.info.desc"));
	}
}

