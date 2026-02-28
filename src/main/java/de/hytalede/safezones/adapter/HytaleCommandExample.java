package de.hytalede.safezones.adapter;

/**
 * Example (non-compiling pseudo snippet) to illustrate how a future Hytale command could call the core.
 *
 * <p>Hytale is not released yet, so we keep the adapter code out of this core module. When the Hytale
 * SDK is available, you would implement a command that:\n
 * <ul>
 *   <li>Parses args</li>
 *   <li>Resolves current chunk</li>
 *   <li>Builds {@code CommandContext}</li>
 *   <li>Calls {@code SafeZoneCommands.execute(...)} and prints the returned message</li>
 * </ul>
 * </p>
 *
 * <p>Reference from CurseForge (as provided):</p>
 *
 * <pre>
 * public class ModdedCommand extends CommandBase {
 *
 *     public ModdedCommand() {
 *         super(\"moddedcommand\", \"A test command\", false);
 *     }
 *
 *     @Override
 *     protected void executeSync(@Nonnull CommandContext commandContext) {
 *         commandContext.senderAsPlayer().getWorld().execute(() -> {
 *             EventTitleUtil.showEventTitleToPlayer(
 *                 commandContext.senderAsPlayer().getReference(),
 *                 Message.raw(\"It's modded!\"),
 *                 Message.raw(\"Yeppers\"),
 *                 true,
 *                 commandContext.senderAsPlayer().getWorld().getEntityStore().getStore());
 *         });
 *     }
 * }
 * </pre>
 */
public final class HytaleCommandExample {
	private HytaleCommandExample() {
	}
}

