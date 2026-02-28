package de.hytalede.safezones.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JsonUtil {
	private JsonUtil() {
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

	static Map<String, String> getStringMap(Map<String, Object> root, String key, boolean required) {
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

		for (Object entryObj : map.entrySet()) {
			if (!(entryObj instanceof Map.Entry<?, ?> entry)) {
				continue;
			}
			if (!(entry.getKey() instanceof String)) {
				throw new IllegalArgumentException("Non-string key in " + key);
			}
			if (!(entry.getValue() instanceof String)) {
				throw new IllegalArgumentException("Non-string value in " + key + "." + entry.getKey());
			}
		}

		@SuppressWarnings("unchecked")
		Map<String, String> casted = (Map<String, String>) value;
		return casted;
	}

	static Set<String> getStringSet(Map<String, Object> root, String key, boolean required) {
		Object value = root.get(key);
		if (value == null) {
			if (required) {
				throw new IllegalArgumentException("Missing key: " + key);
			}
			return Set.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException("Key '" + key + "' must be an array");
		}
		Set<String> out = new LinkedHashSet<>();
		for (Object item : list) {
			if (!(item instanceof String s)) {
				throw new IllegalArgumentException("Key '" + key + "' must contain only strings");
			}
			if (!s.isBlank()) {
				out.add(s);
			}
		}
		return Set.copyOf(out);
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

	static Double getDoubleBoxed(Map<String, Object> root, String key) {
		Object value = root.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof String s) {
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Key '" + key + "' must be a number");
			}
		}
		throw new IllegalArgumentException("Key '" + key + "' must be a number");
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

