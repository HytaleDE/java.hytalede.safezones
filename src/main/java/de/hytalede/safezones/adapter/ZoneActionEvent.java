package de.hytalede.safezones.adapter;

import de.hytalede.safezones.core.Decision;
import de.hytalede.safezones.core.ZoneActionRequest;

/**
 * Adapter contract: wrap a platform/server event into an engine request that can be allowed/denied.
 *
 * <p>The platform implementation typically:
 * <ul>
 *   <li>Builds a {@link ZoneActionRequest} from event details (actor, chunk, y, etc.)</li>
 *   <li>Calls the handler</li>
 *   <li>If denied, cancels the underlying event / clears targets / blocks physics</li>
 * </ul>
 */
public interface ZoneActionEvent {
	ZoneActionRequest request();

	/**
	 * Enforce a denial on the underlying platform event (cancel, null target, etc.).
	 */
	void deny(Decision decision);
}

