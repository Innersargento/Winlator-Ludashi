package com.winlator.cmod.contentdialog;

import java.util.HashMap;
import java.util.Map;

/**
 * The `key=value;` string both OpenGL driver dialogs persist into the container.
 *
 * One string covers both drivers rather than one per driver, so switching from
 * zink to freedreno and back does not lose what was set on the other side. The
 * dialog for a driver reads and rewrites only its own keys; the rest ride along
 * untouched.
 */
public abstract class OpenglDriverConfig {

    public static HashMap<String, String> parse(String config) {
        HashMap<String, String> mapped = new HashMap<>();
        if (config == null) return mapped;

        for (String element : config.split(";")) {
            if (element.isEmpty()) continue;
            /* limit -1 keeps an empty value as an empty string rather than
             * dropping the key, which is what an unset flag list looks like.
             */
            String[] pair = element.split("=", -1);
            mapped.put(pair[0], pair.length > 1 ? pair[1] : "");
        }
        return mapped;
    }

    public static String write(HashMap<String, String> config) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (out.length() > 0) out.append(';');
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    /**
     * A key the caller may never have written -- a container from before this
     * dialog existed, or the other driver's half of the string.
     */
    public static String get(HashMap<String, String> config, String key, String fallback) {
        String value = config.get(key);
        return value != null ? value : fallback;
    }

    public static boolean isEnabled(HashMap<String, String> config, String key, boolean fallback) {
        String value = config.get(key);
        return value != null ? value.equals("1") : fallback;
    }

    /**
     * Comma-separated flag list to the array MultiSelectionComboBox wants.
     * "".split(",") yields one empty element, which would land in the selected
     * set and come back out as a stray leading comma.
     */
    public static String[] splitFlags(String flags) {
        return flags.isEmpty() ? new String[0] : flags.split(",");
    }
}
