# HytaleDE SafeZones (Core)

English documentation. German version: [README.de.md](README.de.md)

## What is this?

This project is a **pure Java core library** for chunk-based SafeZones / region protection:
- Zones are defined as **rectangles in chunk coordinates (x/z)**.
- Per-chunk overrides: **player owner chunk**, **community chunk**, and **per-player permissions**.
- Full protection flags (damage/PvP, mobs, build/mine, interact/containers, explosions, fire/liquids, pistons, projectiles).

Because the **Hytale server event/permission APIs are not defined yet**, this module intentionally does **not** depend on any Hytale SDK.
A future adapter should map Hytale events to core actions and enforce allow/deny decisions.

## Commands (adapter-side)

These commands are implemented adapter-agnostic in `de.hytalede.safezones.commands.SafeZoneCommands` and can be wired to any runtime command API.

- **Selection / zones**
  - `/safezone start`: select start chunk (corner 1)
  - `/safezone end`: create a zone rectangle from start to current chunk (corner 3)
  - `/safezone list` (Admin): list all zones
  - `/safezone remove <zoneId>` (Admin): remove a zone by id

- **Claims / permissions**
  - `/safezone claim` (Admin): mark current chunk as **community chunk**
  - `/safezone claim <player>` (Mod/Admin): mark current chunk as **player-owned** and grant owner build+mine
  - `/safezone unclaim` (Admin): remove claim/overrides for current chunk
  - `/safezone trust <player>` (Mod/Admin): grant build+mine for current chunk (without changing owner)
  - `/safezone untrust <player>` (Mod/Admin): remove that per-chunk permission

- **Settings**
  - `/safezone set <setting> <value>` (Admin): change settings of the zone you are currently in
  - `/safezone flag <setting> <value>` (Admin): alias of `set`
  - `/safezone info`: show zone + claim info for current chunk

## Roles / permissions

Roles come from `config.yml` (player names are stored as **lowercase**):
- **Admin**: everything
- **Mod**: can `claim <player>`, `trust`, `untrust`
- **CommunityBuilder**: unlimited build/mine in community chunks

## Configuration (JSON)

Example files:
- `src/main/resources/config.json.example` (roles, defaults, messages)
- `src/main/resources/zones.json.example` (zones and chunk overrides)

## Protection (Hytale adapter)

The Hytale plugin adapter enforces zone rules via ECS systems and packet guards:

- **Block break/place**: `BreakBlockEvent`, `PlaceBlockEvent`; build/mine and height/depth limits apply.
- **Block use (interact)**: `UseBlockEvent.Pre` — opening UIs, doors, etc. Restricted in player-owned claims (owner/trusted with allowInteract).
- **Harvest (sickle on crops)**: Treated as mine-like; requires **canMine** (or owner/trusted in that claim). Dig depth is not applied to harvest use so trusted players can sickle-harvest.
- **F-key pickup / fluid bucket**: Interaction chains are cancelled before the interaction manager when the player may not mine or place fluid in that cell.

## Changelog

### 0.8.0

- **Harvest (sickle on crops)**: Block use that harvests (sickle/crop heuristic) is gated by **canMine**; owner and trusted can harvest (fallback when BLOCK_BREAK denies). Harvest use does not apply dig-depth limit (Y passed as null).
- **Fluid bucket / F-pickup guards**: Fixed world-thread crash by using real Hytale protocol types: `SyncInteractionChain.data` (`InteractionChainData`), `BlockPosition`, protocol `BlockFace`; added `BlockTarget` record and `extractBlockTarget()` (no non-existent types/methods).

## Build

Requires **Eclipse Adoptium Temurin JDK 25** (Maven must run on Java 25 for `--release 25`).

- `mvn test`
- `mvn package`

