package de.hytalede.safezones.adapter;

import de.hytalede.safezones.core.Decision;
import de.hytalede.safezones.core.SafeZonesSnapshot;
import de.hytalede.safezones.core.ZoneEngine;

import java.util.Objects;

/**
 * Small utility to connect platform events with the core engine.
 */
public final class SafeZonesEventHandler {
	private final ZoneEngine engine;

	public SafeZonesEventHandler(ZoneEngine engine) {
		this.engine = Objects.requireNonNull(engine, "engine must not be null");
	}

	public Decision handle(SafeZonesSnapshot snapshot, ZoneActionEvent event) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Objects.requireNonNull(event, "event must not be null");
		Decision decision = engine.decide(snapshot, event.request());
		if (!decision.allowed()) {
			event.deny(decision);
		}
		return decision;
	}
}

