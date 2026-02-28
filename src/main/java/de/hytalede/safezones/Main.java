package de.hytalede.safezones;

import java.nio.file.Path;

/**
 * CLI placeholder.
 *
 * <p>This module is primarily a pure core library. A future Hytale adapter will call the engine from
 * real server events.</p>
 */
public final class Main {
	public static void main(String[] args) {
		System.out.println("HytaleDE SafeZones (Core)");
		System.out.println("This is a core library. Example configs:");
		System.out.println(" - " + Path.of("src/main/resources/config.json.example"));
		System.out.println(" - " + Path.of("src/main/resources/zones.json.example"));
		System.out.println();
		System.out.println("Build:");
		System.out.println("  mvn test");
		System.out.println("  mvn package");
	}
}

