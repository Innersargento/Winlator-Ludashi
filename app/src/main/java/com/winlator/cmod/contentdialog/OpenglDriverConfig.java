package com.winlator.cmod.contentdialog;

import java.util.HashMap;
import java.util.Map;

public abstract class OpenglDriverConfig {

    public static HashMap<String, String> parse(String config) {
        HashMap<String, String> mapped = new HashMap<>();
        if (config == null) return mapped;

        for (String element : config.split(";")) {
            if (element.isEmpty()) continue;
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

    public static String get(HashMap<String, String> config, String key, String fallback) {
        String value = config.get(key);
        return value != null ? value : fallback;
    }

    public static boolean isEnabled(HashMap<String, String> config, String key, boolean fallback) {
        String value = config.get(key);
        return value != null ? value.equals("1") : fallback;
    }

    public static String[] splitFlags(String flags) {
        return flags.isEmpty() ? new String[0] : flags.split(",");
    }
}
