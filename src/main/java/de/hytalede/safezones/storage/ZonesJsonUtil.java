package de.hytalede.safezones.storage;

import java.util.Map;

final class ZonesJsonUtil {
	private ZonesJsonUtil() {
	}

	static Map<String, Object> getMap(Map<String, Object> root, String key, boolean required) {
		Object value = root.get(key);
		if (value == null) {
			if (required) {
				throw new IllegalArgumentException("Missing key: " + key);
			}
			return Map.of();
		}
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException("Key '" + key + "' must be an object");
		}
		return castStringObjectMap(map, key);
	}

	static int getInt(Map<String, Object> root, String key, boolean required) {
		Object value = root.get(key);
		if (value == null) {
			if (required) {
				throw new IllegalArgumentException("Missing key: " + key);
			}
			return 0;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String s) {
			try {
				return Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Key '" + key + "' must be an int");
			}
		}
		throw new IllegalArgumentException("Key '" + key + "' must be an int");
	}

	static Integer getIntBoxed(Map<String, Object> root, String key) {
		Object value = root.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String s) {
			try {
				return Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Key '" + key + "' must be an int");
			}
		}
		throw new IllegalArgumentException("Key '" + key + "' must be an int");
	}

	static Boolean getBooleanBoxed(Map<String, Object> root, String key) {
		Object value = root.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean b) {
			return b;
		}
		if (value instanceof String s) {
			return Boolean.parseBoolean(s);
		}
		throw new IllegalArgumentException("Key '" + key + "' must be a boolean");
	}

	static String getString(Map<String, Object> root, String key, boolean required) {
		Object value = root.get(key);
		if (value == null) {
			if (required) {
				throw new IllegalArgumentException("Missing key: " + key);
			}
			return "";
		}
		if (!(value instanceof String s)) {
			throw new IllegalArgumentException("Key '" + key + "' must be a string");
		}
		if (required && s.isBlank()) {
			throw new IllegalArgumentException("Key '" + key + "' must not be blank");
		}
		return s;
	}

	static Map<String, Object> castStringObjectMap(Map<?, ?> raw, String name) {
		for (Object entryObj : raw.entrySet()) {
			if (!(entryObj instanceof Map.Entry<?, ?> entry)) {
				continue;
			}
			if (!(entry.getKey() instanceof String)) {
				throw new IllegalArgumentException("Non-string key in object: " + name);
			}
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> casted = (Map<String, Object>) raw;
		return casted;
	}
}

