package de.hytalede.safezones.commands;

import de.hytalede.safezones.config.SafeZonesConfig;
import de.hytalede.safezones.core.ChunkOverride;
import de.hytalede.safezones.core.ChunkPos;
import de.hytalede.safezones.core.CellType;
import de.hytalede.safezones.core.ClaimType;
import de.hytalede.safezones.core.PermissionOverride;
import de.hytalede.safezones.core.PlayerNames;
import de.hytalede.safezones.core.SafeZone;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneRect;
import de.hytalede.safezones.core.ZoneSettings;
import de.hytalede.safezones.core.ZoneSettingsPatch;
import de.hytalede.safezones.storage.SafeZonesRepository;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter-agnostic command executor.
 *
 * <p>Parsing is intentionally simple and can be wrapped by any runtime command API.</p>
 */
public final class SafeZoneCommands {
	private final SafeZonesConfig config;
	private final SafeZonesRepository repository;
	private final SelectionStore selections;

	public SafeZoneCommands(SafeZonesConfig config, SafeZonesRepository repository, SelectionStore selections) {
		this.config = config;
		this.repository = repository;
		this.selections = selections;
	}

	public CommandResponse execute(String[] args, CommandContext ctx) throws IOException {
		if (args == null || args.length == 0) {
			return usage(ctx.locale());
		}

		String sub = args[0].toLowerCase(Locale.ROOT);
		return switch (sub) {
			case "start" -> cmdStart(ctx);
			case "end" -> cmdEnd(ctx);
			case "edit" -> cmdEdit(args, ctx);
			case "name" -> cmdName(args, ctx);
			case "title" -> cmdTitle(args, ctx);
			case "claim" -> cmdClaim(args, ctx);
			case "unclaim" -> cmdUnclaim(ctx);
			case "trust" -> cmdTrust(args, ctx);
			case "untrust" -> cmdUntrust(args, ctx);
			case "set" -> cmdSet(args, ctx);
			case "flag" -> cmdSet(args, ctx); // alias
			case "info" -> cmdInfo(ctx);
			case "list" -> cmdList(ctx);
			case "remove" -> cmdRemove(args, ctx);
			default -> usage(ctx.locale());
		};
	}

	private CommandResponse cmdStart(CommandContext ctx) {
		selections.setStartChunk(ctx.senderNameLower(), ctx.currentChunk());
		return CommandResponse.ok("selectionStart", "Start chunk selected: " + ctx.currentChunk().toKey());
	}

	private CommandResponse cmdEdit(String[] args, CommandContext ctx) throws IOException {
		// /safezone edit         -> edit zone at current chunk
		// /safezone edit <id>    -> edit zone by id
		if (ctx.senderRole() == de.hytalede.safezones.core.Role.PLAYER) {
			return noPerm(ctx.locale());
		}

		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		SafeZone zone;

		if (args.length == 1) {
			Optional<SafeZone> zoneOpt = snapshot.findZoneFor(ctx.currentChunk());
			if (zoneOpt.isEmpty()) {
				return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
			}
			zone = zoneOpt.get();
		} else if (args.length == 2) {
			String id = args[1];
			zone = snapshot.zones().stream()
					.filter(z -> z.id().equalsIgnoreCase(id))
					.findFirst()
					.orElse(null);
			if (zone == null) {
				return CommandResponse.error("zoneNotFound", "Zone not found: " + id);
			}
		} else {
			return usage(ctx.locale());
		}

		selections.setEditingZoneId(ctx.senderNameLower(), zone.id());
		return CommandResponse.ok(
				"zoneEdit",
				"Editing zone " + zone.id() + ". Use /safezone start and /safezone end to set new bounds (settings/claims stay)."
		);
	}

	private CommandResponse cmdEnd(CommandContext ctx) throws IOException {
		ChunkPos start = selections.getStartChunk(ctx.senderNameLower());
		if (start == null) {
			return CommandResponse.error("selectionMissing", "Use /safezone start first.");
		}
		ChunkPos end = ctx.currentChunk();

		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		ZoneRect rect = ZoneRect.fromCorners(start, end);
		String editId = selections.getEditingZoneId(ctx.senderNameLower());

		var zones = new java.util.ArrayList<>(snapshot.zones());
		String id;

		if (editId != null && !editId.isBlank()) {
			for (int i = 0; i < zones.size(); i++) {
				SafeZone z = zones.get(i);
				if (z.id().equalsIgnoreCase(editId)) {
					zones.set(i, new SafeZone(z.id(), z.title(), rect, z.settings()));
					id = z.id();
					SafeZonesSnapshot updated = new SafeZonesSnapshot(zones, snapshot.chunkOverrides());
					repository.saveSnapshot(updated);
					selections.clearStartChunk(ctx.senderNameLower());
					selections.clearEditingZoneId(ctx.senderNameLower());
					return CommandResponse.ok(
							"zoneUpdated",
							"SafeZone updated: " + id + " (" + rect.minX() + "," + rect.minZ() + " -> " + rect.maxX() + "," + rect.maxZ() + ")"
					);
				}
			}
			// Editing id was set, but zone no longer exists.
			return CommandResponse.error("zoneNotFound", "Zone not found: " + editId + " (editing mode active)");
		}

		// Create new zone
		id = nextZoneId(snapshot);
		SafeZone zone = new SafeZone(id, null, rect, config.defaults());
		zones.add(zone);
		repository.saveSnapshot(new SafeZonesSnapshot(zones, snapshot.chunkOverrides()));
		selections.clearStartChunk(ctx.senderNameLower());

		return CommandResponse.ok("zoneCreated", "SafeZone created: " + id + " (" + rect.minX() + "," + rect.minZ() + " -> " + rect.maxX() + "," + rect.maxZ() + ")");
	}

	private CommandResponse cmdName(String[] args, CommandContext ctx) throws IOException {
		// /safezone name <NAME...>  -> rename zone at current cell
		if (ctx.senderRole() != de.hytalede.safezones.core.Role.ADMIN) {
			return noPerm(ctx.locale());
		}
		if (args.length < 2) {
			return usage(ctx.locale());
		}
		String newId = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
		if (newId.isBlank()) {
			return CommandResponse.error("invalidName", "Name must not be blank.");
		}

		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zoneOpt = snapshot.findZoneFor(ctx.currentChunk());
		if (zoneOpt.isEmpty()) {
			return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}

		SafeZone current = zoneOpt.get();
		String oldId = current.id();
		if (oldId.equalsIgnoreCase(newId)) {
			return CommandResponse.ok("zoneUpdated", "Zone name unchanged: " + oldId);
		}

		boolean exists = snapshot.zones().stream().anyMatch(z -> z.id().equalsIgnoreCase(newId));
		if (exists) {
			return CommandResponse.error("nameTaken", "A zone with that name already exists: " + newId);
		}

		var zones = new java.util.ArrayList<>(snapshot.zones());
		for (int i = 0; i < zones.size(); i++) {
			SafeZone z = zones.get(i);
			if (z.id().equalsIgnoreCase(oldId)) {
				zones.set(i, new SafeZone(newId, z.title(), z.rect(), z.settings()));
				break;
			}
		}
		repository.saveSnapshot(new SafeZonesSnapshot(zones, snapshot.chunkOverrides()));

		// If the sender is currently editing this zone, update their editing id.
		String editing = selections.getEditingZoneId(ctx.senderNameLower());
		if (editing != null && editing.equalsIgnoreCase(oldId)) {
			selections.setEditingZoneId(ctx.senderNameLower(), newId);
		}

		return CommandResponse.ok("zoneUpdated", "Zone renamed: " + oldId + " -> " + newId);
	}

	private CommandResponse cmdTitle(String[] args, CommandContext ctx) throws IOException {
		// /safezone title <TITLE...>  -> set title (top line) for zone at current cell
		if (ctx.senderRole() != de.hytalede.safezones.core.Role.ADMIN) {
			return noPerm(ctx.locale());
		}
		if (args.length < 2) {
			return usage(ctx.locale());
		}
		String title = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
		if (title.isBlank()) {
			return CommandResponse.error("invalidTitle", "Title must not be blank.");
		}

		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zoneOpt = snapshot.findZoneFor(ctx.currentChunk());
		if (zoneOpt.isEmpty()) {
			return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}
		SafeZone current = zoneOpt.get();

		var zones = new java.util.ArrayList<>(snapshot.zones());
		for (int i = 0; i < zones.size(); i++) {
			SafeZone z = zones.get(i);
			if (z.id().equalsIgnoreCase(current.id())) {
				zones.set(i, new SafeZone(z.id(), title, z.rect(), z.settings()));
				break;
			}
		}
		repository.saveSnapshot(new SafeZonesSnapshot(zones, snapshot.chunkOverrides()));
		return CommandResponse.ok("zoneUpdated", "Zone title updated for " + current.id() + ": " + title);
	}

	private CommandResponse cmdClaim(String[] args, CommandContext ctx) throws IOException {
		// /safezone claim <player>  -> mod+: claim a HOUSE lot for a player
		if (args.length == 2) {
			if (ctx.senderRole() != de.hytalede.safezones.core.Role.ADMIN && ctx.senderRole() != de.hytalede.safezones.core.Role.MOD) {
				return noPerm(ctx.locale());
			}
			return claimPlayer(args[1], ctx);
		}
		return usage(ctx.locale());
	}

	private CommandResponse claimPlayer(String player, CommandContext ctx) throws IOException {
		String playerLower = PlayerNames.normalize(player);
		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zone = snapshot.findZoneFor(ctx.currentChunk());
		if (zone.isEmpty()) {
			return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}
		SafeZone currentZone = zone.get();

		Map<ChunkPos, ChunkOverride> overrides = new LinkedHashMap<>(snapshot.chunkOverrides());
		ChunkOverride existing = overrides.getOrDefault(ctx.currentChunk(), ChunkOverride.none());
		if (existing.type() != CellType.HOUSE) {
			return CommandResponse.error("notHouse", "This cell is not a house lot. Use /safezone set house first.");
		}
		if (existing.claimType() != ClaimType.NONE) {
			return CommandResponse.error("alreadyClaimed", "This lot is already claimed.");
		}

		// Enforce claim limits (global always applies; per-zone applies in addition).
		SafeZonesConfig.ClaimLimitsConfig limits = config.claimLimits();
		if (limits != null) {
			int globalLimit = limits.globalPerPlayer();
			if (globalLimit > 0) {
				int currentGlobal = countClaimsOwnedBy(snapshot, playerLower, null);
				if (currentGlobal >= globalLimit) {
					return CommandResponse.error(
							"claimLimitGlobal",
							config.message(ctx.locale(), "claimLimitGlobal") + " (" + currentGlobal + "/" + globalLimit + ")"
					);
				}
			}

			Integer zoneLimitBoxed = limits.perZoneLimit(currentZone.id());
			int zoneLimit = zoneLimitBoxed != null ? zoneLimitBoxed.intValue() : 0;
			if (zoneLimit > 0) {
				int currentInZone = countClaimsOwnedBy(snapshot, playerLower, currentZone);
				if (currentInZone >= zoneLimit) {
					return CommandResponse.error(
							"claimLimitZone",
							config.message(ctx.locale(), "claimLimitZone")
									+ " zone=" + currentZone.id()
									+ " (" + currentInZone + "/" + zoneLimit + ")"
					);
				}
			}
		}

		Map<String, PermissionOverride> po = new LinkedHashMap<>(existing.playerOverrides());
		// Claim implies the owner can build/mine with per-lot height limits:
		// 12 blocks down, 24 blocks up relative to the claimed lot's ground reference.
		// Also: owner can interact/use containers/use entities in their claim.
		po.put(playerLower, new PermissionOverride(true, true, 24, 12, true, true, true, null, null));

		Integer groundY = ctx.currentY();
		ChunkOverride updated = new ChunkOverride(existing.type(), ClaimType.PLAYER_OWNER, playerLower, groundY, po);
		overrides.put(ctx.currentChunk(), updated);

		repository.saveSnapshot(new SafeZonesSnapshot(snapshot.zones(), overrides));
		return CommandResponse.ok("claimSet", "Chunk claimed for: " + playerLower + " (" + ctx.currentChunk().toKey() + ")");
	}

	/**
	 * Count how many player-owned HOUSE claims exist for {@code ownerLower}.
	 *
	 * @param zoneScope if non-null, only count claims inside that zone rect
	 */
	private static int countClaimsOwnedBy(SafeZonesSnapshot snapshot, String ownerLower, SafeZone zoneScope) {
		if (snapshot == null || ownerLower == null || ownerLower.isBlank()) return 0;
		int count = 0;
		for (Map.Entry<ChunkPos, ChunkOverride> e : snapshot.chunkOverrides().entrySet()) {
			ChunkOverride ov = e.getValue();
			if (ov == null) continue;
			if (ov.claimType() != ClaimType.PLAYER_OWNER) continue;
			String owner = ov.owner();
			if (owner == null || !owner.equalsIgnoreCase(ownerLower)) continue;
			if (zoneScope != null && (zoneScope.rect() == null || !zoneScope.rect().contains(e.getKey()))) continue;
			count++;
		}
		return count;
	}

	private CommandResponse cmdUnclaim(CommandContext ctx) throws IOException {
		if (ctx.senderRole() != de.hytalede.safezones.core.Role.ADMIN) {
			return noPerm(ctx.locale());
		}
		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		ChunkOverride existing = snapshot.chunkOverrides().get(ctx.currentChunk());
		if (existing == null) {
			return CommandResponse.ok("claimCleared", "No claim set for: " + ctx.currentChunk().toKey());
		}
		if (existing.type() != CellType.HOUSE && existing.type() != CellType.FREE) {
			return CommandResponse.error("unclaimNotAllowed", "This cell type is not unclaimable.");
		}
		Map<ChunkPos, ChunkOverride> overrides = new LinkedHashMap<>(snapshot.chunkOverrides());
		if (existing.type() == CellType.HOUSE) {
			// Keep type=HOUSE, but clear claim + overrides
			ChunkOverride cleared = new ChunkOverride(CellType.HOUSE, ClaimType.NONE, null, null, Map.of());
			overrides.put(ctx.currentChunk(), cleared);
		} else {
			// FREE area goes back to completely unset.
			overrides.remove(ctx.currentChunk());
		}
		repository.saveSnapshot(new SafeZonesSnapshot(snapshot.zones(), overrides));
		return CommandResponse.ok("claimCleared", "Lot is now free: " + ctx.currentChunk().toKey());
	}

	private CommandResponse cmdTrust(String[] args, CommandContext ctx) throws IOException {
		// /safezone trust <player>
		if (args.length != 2) {
			return usage(ctx.locale());
		}

		String playerLower = PlayerNames.normalize(args[1]);
		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zone = snapshot.findZoneFor(ctx.currentChunk());
		if (zone.isEmpty()) {
			return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}

		Map<ChunkPos, ChunkOverride> overrides = new LinkedHashMap<>(snapshot.chunkOverrides());
		ChunkOverride existing = overrides.get(ctx.currentChunk());
		if (existing == null || existing.type() != CellType.HOUSE || existing.claimType() != ClaimType.PLAYER_OWNER || existing.owner() == null || existing.owner().isBlank()) {
			return CommandResponse.error("notPlayerClaim", "This cell is not a player-claimed house lot.");
		}
		// Owner manages trust without special permissions; admins/mods may manage too.
		boolean isStaff = ctx.senderRole() == de.hytalede.safezones.core.Role.ADMIN || ctx.senderRole() == de.hytalede.safezones.core.Role.MOD;
		if (!isStaff && !existing.owner().equalsIgnoreCase(ctx.senderNameLower())) {
			return CommandResponse.error("notOwner", "Only the lot owner may manage trust. Owner=" + existing.owner());
		}
		if (existing.owner().equalsIgnoreCase(playerLower)) {
			return CommandResponse.error("invalidTarget", "Cannot trust the owner of this lot.");
		}
		Map<String, PermissionOverride> po = new LinkedHashMap<>(existing.playerOverrides());
		// Trust implies interacting with blocks/containers/entities too.
		po.put(playerLower, new PermissionOverride(true, true, null, null, true, true, true, null, null));

		ChunkOverride updated = new ChunkOverride(existing.type(), existing.claimType(), existing.owner(), existing.groundY(), po);
		overrides.put(ctx.currentChunk(), updated);

		repository.saveSnapshot(new SafeZonesSnapshot(snapshot.zones(), overrides));
		return CommandResponse.ok("trusted", "Trusted " + playerLower + " in chunk " + ctx.currentChunk().toKey());
	}

	private CommandResponse cmdUntrust(String[] args, CommandContext ctx) throws IOException {
		// /safezone untrust <player>
		if (args.length != 2) {
			return usage(ctx.locale());
		}

		String playerLower = PlayerNames.normalize(args[1]);
		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zone = snapshot.findZoneFor(ctx.currentChunk());
		if (zone.isEmpty()) {
			return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}

		Map<ChunkPos, ChunkOverride> overrides = new LinkedHashMap<>(snapshot.chunkOverrides());
		ChunkOverride existing = overrides.get(ctx.currentChunk());
		if (existing == null || existing.type() != CellType.HOUSE || existing.claimType() != ClaimType.PLAYER_OWNER || existing.owner() == null || existing.owner().isBlank()) {
			return CommandResponse.error("notPlayerClaim", "This cell is not a player-claimed house lot.");
		}
		// Owner manages trust without special permissions; admins/mods may manage too.
		boolean isStaff = ctx.senderRole() == de.hytalede.safezones.core.Role.ADMIN || ctx.senderRole() == de.hytalede.safezones.core.Role.MOD;
		if (!isStaff && !existing.owner().equalsIgnoreCase(ctx.senderNameLower())) {
			return CommandResponse.error("notOwner", "Only the lot owner may manage trust. Owner=" + existing.owner());
		}
		if (existing.owner().equalsIgnoreCase(playerLower)) {
			return CommandResponse.error("invalidTarget", "Cannot untrust the owner of this lot.");
		}
		Map<String, PermissionOverride> po = new LinkedHashMap<>(existing.playerOverrides());
		po.remove(playerLower);

		// Keep the plot claim + type; only remove the per-player entry.
		ChunkOverride updated = new ChunkOverride(existing.type(), existing.claimType(), existing.owner(), existing.groundY(), po);
		overrides.put(ctx.currentChunk(), updated);

		repository.saveSnapshot(new SafeZonesSnapshot(snapshot.zones(), overrides));
		return CommandResponse.ok("untrusted", "Untrusted " + playerLower + " in chunk " + ctx.currentChunk().toKey());
	}

	private CommandResponse cmdSet(String[] args, CommandContext ctx) throws IOException {
		if (ctx.senderRole() != de.hytalede.safezones.core.Role.ADMIN) {
			return noPerm(ctx.locale());
		}
		// /safezone set <type>        -> set cell type (16x16)
		// /safezone set <key> <value> -> zone settings
		if (args.length == 2) {
			return cmdSetCellType(args[1], ctx);
		}
		if (args.length != 3) {
			return usage(ctx.locale());
		}

		String key = args[1];
		String value = args[2];

		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zoneOpt = snapshot.findZoneFor(ctx.currentChunk());
		if (zoneOpt.isEmpty()) {
			return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}

		SafeZone zone = zoneOpt.get();
		ZoneSettingsPatch patch = patchForSingleSetting(key, value);
		ZoneSettings newSettings = patch.applyOn(zone.settings());

		var zones = new java.util.ArrayList<>(snapshot.zones());
		for (int i = 0; i < zones.size(); i++) {
			if (zones.get(i).id().equalsIgnoreCase(zone.id())) {
				zones.set(i, new SafeZone(zone.id(), zone.title(), zone.rect(), newSettings));
				break;
			}
		}
		repository.saveSnapshot(new SafeZonesSnapshot(zones, snapshot.chunkOverrides()));
		return CommandResponse.ok("zoneUpdated", "Updated zone " + zone.id() + ": " + key + "=" + value);
	}

	private CommandResponse cmdSetCellType(String rawType, CommandContext ctx) throws IOException {
		if (rawType == null || rawType.isBlank()) {
			return CommandResponse.error("invalidType", "Type must be one of: free, house, street, wall, gate, spawn, market, community");
		}
		String rawLower = rawType.trim().toLowerCase(Locale.ROOT);
		boolean community = rawLower.equals("community");

		CellType type;
		try {
			type = community ? CellType.FREE : CellType.parse(rawType);
		} catch (IllegalArgumentException e) {
			return CommandResponse.error("unknownType", e.getMessage());
		}
		if (type == CellType.NONE) {
			return CommandResponse.error("invalidType", "Type must be one of: free, house, street, wall, gate, spawn, market, community");
		}

		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zoneOpt = snapshot.findZoneFor(ctx.currentChunk());
		if (zoneOpt.isEmpty()) {
			return CommandResponse.error("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}

		Map<ChunkPos, ChunkOverride> overrides = new LinkedHashMap<>(snapshot.chunkOverrides());
		ChunkOverride existing = overrides.getOrDefault(ctx.currentChunk(), ChunkOverride.none());

		// /safezone set free -> reset to "unset/white" (only if not player-claimed)
		if (type == CellType.FREE) {
			if (existing.claimType() == ClaimType.PLAYER_OWNER) {
				return CommandResponse.error("claimedByPlayer", "Cannot set free: this area is claimed by a player.");
			}
			if (!community) {
				overrides.remove(ctx.currentChunk());
				repository.saveSnapshot(new SafeZonesSnapshot(snapshot.zones(), overrides));
				return CommandResponse.ok("cellTypeSet", "Cell reset to free/unset: " + ctx.currentChunk().toKey());
			}
		}

		ChunkOverride updated;
		if (community) {
			// Community claim is only allowed on truly free/unset cells.
			if (existing.type() != CellType.NONE || existing.claimType() != ClaimType.NONE) {
				return CommandResponse.error("notFreeArea", "This cell is not a free/unset area.");
			}
			Integer groundY = ctx.currentY();
			updated = new ChunkOverride(CellType.FREE, ClaimType.COMMUNITY, null, groundY, Map.of());
		} else if (type == CellType.HOUSE) {
			// A house lot starts as "free" (green): type=HOUSE, claimType=NONE
			updated = new ChunkOverride(CellType.HOUSE, ClaimType.NONE, null, null, Map.of());
		} else {
			// Other types are just marked and unclaimable for now.
			updated = new ChunkOverride(type, existing.claimType(), existing.owner(), existing.groundY(), existing.playerOverrides());
			// Ensure it cannot be claimed as a player lot.
			if (updated.claimType() == ClaimType.PLAYER_OWNER) {
				updated = new ChunkOverride(type, ClaimType.NONE, null, null, Map.of());
			}
		}

		overrides.put(ctx.currentChunk(), updated);
		repository.saveSnapshot(new SafeZonesSnapshot(snapshot.zones(), overrides));
		String label = community ? "community" : type.name().toLowerCase(Locale.ROOT);
		return CommandResponse.ok("cellTypeSet", "Cell type set to " + label + " at " + ctx.currentChunk().toKey());
	}

	private ZoneSettingsPatch patchForSingleSetting(String key, String value) {
		return switch (key.toLowerCase(Locale.ROOT)) {
			case "pvp" -> new ZoneSettingsPatch(parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
			case "takedamage" -> new ZoneSettingsPatch(null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
			case "allowprojectiles" -> new ZoneSettingsPatch(null, null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
			case "allowexplosiondamage" -> new ZoneSettingsPatch(null, null, null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
			case "spawnmobs" -> new ZoneSettingsPatch(null, null, null, null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
			case "allowmobtargetplayers" -> new ZoneSettingsPatch(null, null, null, null, null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null);
			case "denymobenter" -> new ZoneSettingsPatch(null, null, null, null, null, null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null, null);
			case "canbuild" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null, null);
			case "canmine" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, parseBool(value), null, null, null, null, null, null, null, null, null, null, null);
			case "ground", "groundy" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, parseInt(value), null, null, null, null, null, null, null, null, null, null);
			case "buildheight" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, parseInt(value), null, null, null, null, null, null, null, null, null);
			case "digdepth" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, parseInt(value), null, null, null, null, null, null, null, null);
			case "allowinteract" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value), null, null, null, null, null, null, null);
			case "allowcontainers" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value), null, null, null, null, null, null);
			case "allowuseentities" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value), null, null, null, null, null);
			case "allowitemdrop" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value), null, null, null, null);
			case "allowitempickup" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value), null, null, null);
			case "preventfirespread" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value), null, null);
			case "denyliquidflow" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value), null);
			case "denypistons" -> new ZoneSettingsPatch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, parseBool(value));
			default -> throw new IllegalArgumentException("Unknown setting: " + key);
		};
	}

	private CommandResponse cmdInfo(CommandContext ctx) throws IOException {
		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		Optional<SafeZone> zoneOpt = snapshot.findZoneFor(ctx.currentChunk());
		if (zoneOpt.isEmpty()) {
			return CommandResponse.ok("zoneNotFound", config.message(ctx.locale(), "zoneNotFound"));
		}
		SafeZone z = zoneOpt.get();
		ChunkOverride ov = snapshot.chunkOverrideOrNone(ctx.currentChunk());

		// Summary of how many cells exist per type across the whole snapshot.
		EnumMap<CellType, Integer> totalByType = new EnumMap<>(CellType.class);
		int houseTotal = 0;
		int houseFree = 0;
		for (ChunkOverride co : snapshot.chunkOverrides().values()) {
			if (co == null) continue;
			CellType t = co.type();
			if (t == null) t = CellType.NONE;
			if (t != CellType.NONE) {
				totalByType.merge(t, 1, Integer::sum);
			}
			if (t == CellType.HOUSE) {
				houseTotal++;
				if (co.claimType() == ClaimType.NONE) {
					houseFree++;
				}
			}
		}

		StringBuilder areas = new StringBuilder();
		areas.append("Areas: ");
		boolean first = true;

		// Prefer a stable, human-friendly order.
		CellType[] order = new CellType[]{
				CellType.HOUSE,
				CellType.STREET,
				CellType.MARKET,
				CellType.SPAWN,
				CellType.GATE,
				CellType.WALL,
				CellType.FREE
		};
		for (CellType t : order) {
			int total = totalByType.getOrDefault(t, 0);
			if (t == CellType.HOUSE) {
				// Always show House, even if 0, because it's the most important for claims.
				if (!first) areas.append(" | ");
				first = false;
				areas.append("House: ").append(houseFree).append(" (free) / ").append(houseTotal).append(" (total)");
				continue;
			}
			if (total <= 0) continue;
			if (!first) areas.append(" | ");
			first = false;
			areas.append(capitalize(t.name().toLowerCase(Locale.ROOT))).append(": ").append(total);
		}
		if (first) {
			areas.append("No marked cells.");
		}

		String base = "Zone=" + z.id()
				+ " rect=" + z.rect().minX() + "," + z.rect().minZ() + "->" + z.rect().maxX() + "," + z.rect().maxZ()
				+ " type=" + ov.type()
				+ " claim=" + ov.claimType() + (ov.owner() != null ? "(" + ov.owner() + ")" : "");

		return CommandResponse.ok("zoneInfo", base + "\n" + areas);
	}

	private static String capitalize(String s) {
		if (s == null || s.isBlank()) return "";
		if (s.length() == 1) return s.toUpperCase(Locale.ROOT);
		return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	private CommandResponse cmdList(CommandContext ctx) throws IOException {
		if (ctx.senderRole() != de.hytalede.safezones.core.Role.ADMIN) {
			return noPerm(ctx.locale());
		}
		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		if (snapshot.zones().isEmpty()) {
			return CommandResponse.ok("zoneList", "No zones defined.");
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Zones (").append(snapshot.zones().size()).append("): ");
		boolean first = true;
		for (SafeZone z : snapshot.zones()) {
			if (!first) sb.append(" | ");
			first = false;
			sb.append(z.id()).append("[").append(z.rect().minX()).append(",").append(z.rect().minZ()).append("->").append(z.rect().maxX()).append(",").append(z.rect().maxZ()).append("]");
		}
		return CommandResponse.ok("zoneList", sb.toString());
	}

	private CommandResponse cmdRemove(String[] args, CommandContext ctx) throws IOException {
		// /safezone remove <id>
		if (ctx.senderRole() != de.hytalede.safezones.core.Role.ADMIN) {
			return noPerm(ctx.locale());
		}
		if (args.length != 2) {
			return usage(ctx.locale());
		}
		String id = args[1];

		SafeZonesSnapshot snapshot = repository.loadSnapshot();
		var zones = new java.util.ArrayList<>(snapshot.zones());
		boolean removed = zones.removeIf(z -> z.id().equalsIgnoreCase(id));
		if (!removed) {
			return CommandResponse.error("zoneNotFound", "Zone not found: " + id);
		}
		repository.saveSnapshot(new SafeZonesSnapshot(zones, snapshot.chunkOverrides()));
		return CommandResponse.ok("zoneRemoved", "Removed zone: " + id);
	}

	private CommandResponse noPerm(Locale locale) {
		return CommandResponse.error("noPermission", config.message(locale, "noPermission"));
	}

	private CommandResponse usage(Locale locale) {
		return CommandResponse.error("usage",
				"Usage: /safezone start|end|edit [id]|name <name>|title <title>|claim <player>|unclaim|trust <p>|untrust <p>|set <type>|set <k> <v>|info|list|remove <id>");
	}

	private static String nextZoneId(SafeZonesSnapshot snapshot) {
		int i = 1;
		while (true) {
			String id = "zone-" + i;
			boolean exists = snapshot.zones().stream().anyMatch(z -> z.id().equalsIgnoreCase(id));
			if (!exists) {
				return id;
			}
			i++;
		}
	}

	private static boolean parseBool(String s) {
		return Boolean.parseBoolean(s);
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Expected integer, got: " + s);
		}
	}
}

