# HytaleDE SafeZones (Core)

Deutsche Dokumentation. English version: [README.md](README.md)

## Was ist das?

Dieses Projekt ist eine **reine Java-Core-Library** für chunk-basierte **SafeZones/Region-Protection**:
- Zonen sind **Rechtecke in Chunk-Koordinaten (x/z)**.
- Per-Chunk Overrides: **Player-Owner-Chunk**, **Community-Chunk**, plus **Spieler-spezifische Rechte**.
- Vollständige Protection-Flags (Damage/PvP, Mobs, Build/Mine, Interact/Container, Explosions, Fire/Liquids, Pistons, Projectiles).

Da **Hytale-Server Events und Permissions** aktuell noch nicht feststehen, hat dieses Projekt absichtlich **keine Abhängigkeit** zu einem Hytale-SDK.
Ein späterer *Runtime-Adapter* soll:
1. In die passenden Hytale-Events hooken (Damage, BlockBreak/Place, Targeting, Spawns, Physics...).
2. Den aktuellen Chunk und Aktionsdetails bestimmen.
3. Die Core-Engine aufrufen und die Entscheidung durchsetzen (Event canceln, Target entfernen usw.).

## Befehle (Adapter-Seite)

Die Befehle sind adapter-agnostisch in `de.hytalede.safezones.commands.SafeZoneCommands` implementiert und können später an ein Hytale-Command-System gebunden werden.

- **Auswahl / Zonen**
  - `/safezone start`: Start-Chunk auswählen (Ecke 1)
  - `/safezone end`: Zone als Rechteck von Start bis aktuellen Chunk erstellen (diagonale Ecke 3)
  - `/safezone list` (Admin): alle Zonen auflisten
  - `/safezone remove <zoneId>` (Admin): Zone nach ID entfernen

- **Claims / Rechte**
  - `/safezone claim` (Admin): aktuellen Chunk als **Community-Chunk** markieren
  - `/safezone claim <player>` (Mod/Admin): aktuellen Chunk als **Player-Owner-Chunk** markieren + Owner darf bauen/minen
  - `/safezone unclaim` (Admin): Claim/Overrides im aktuellen Chunk entfernen
  - `/safezone trust <player>` (Mod/Admin): Spieler darf in diesem Chunk bauen/minen (ohne Owner zu ändern)
  - `/safezone untrust <player>` (Mod/Admin): diese Rechte wieder entfernen

- **Settings**
  - `/safezone set <setting> <value>` (Admin): Zone-Settings der Zone, in der man steht, ändern
  - `/safezone flag <setting> <value>` (Admin): Alias von `set`
  - `/safezone info`: Zone + Claim-Info für aktuellen Chunk anzeigen

## Rollen / Rechte-System

Rollen kommen aus `config.yml` (Spielernamen werden **lowercase** gespeichert):
- **Admin**: alles
- **Mod**: `claim <player>`, `trust`, `untrust`
- **Community Builder**: in Community-Chunks bauen/minen ohne Limits

## Konfiguration (JSON)

Beispiel-Dateien:
- `src/main/resources/config.json.example` (Rollen, Defaults, Messages)
- `src/main/resources/zones.json.example` (Zonen und Chunk-Overrides)

## Build

Benötigt **Eclipse Adoptium Temurin JDK 25** (Maven muss auf Java 25 laufen wegen `--release 25`).

- `mvn test`
- `mvn package`

