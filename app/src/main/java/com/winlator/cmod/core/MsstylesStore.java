package com.winlator.cmod.core;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class MsstylesStore {
    public static final String EXTENSION = ".msstyles";
    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_.-]");

    private static File getDir(Context context) {
        return new File(context.getFilesDir(), "msstyles");
    }

    public static File getFile(Context context, String name) {
        return new File(getDir(context), name + EXTENSION);
    }

    public static boolean exists(Context context, String name) {
        return getFile(context, name).isFile();
    }

    public static List<String> list(Context context) {
        List<String> names = new ArrayList<>();
        File[] files = getDir(context).listFiles((dir, fileName) -> fileName.endsWith(EXTENSION));
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String fileName = file.getName();
                names.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
            }
        }
        return names;
    }

    public static String importFrom(Context context, Uri uri, String displayName) {
        String name = displayName != null ? displayName : "theme";
        if (name.toLowerCase().endsWith(EXTENSION)) name = name.substring(0, name.length() - EXTENSION.length());
        name = INVALID_NAME_CHARS.matcher(name).replaceAll("_");
        if (name.isEmpty()) name = "theme";

        File dir = getDir(context);
        dir.mkdirs();
        File dstFile = getFile(context, name);
        if (!FileUtils.copy(context, uri, dstFile) || !isPeImage(dstFile)) {
            dstFile.delete();
            return null;
        }
        return name;
    }

    public static String[] getVariants(Context context, String name) {
        String color = "NormalColor";
        String size = "NormalSize";
        try {
            byte[] data = Files.readAllBytes(getFile(context, name).toPath());
            for (Charset charset : new Charset[]{StandardCharsets.UTF_16LE, StandardCharsets.ISO_8859_1}) {
                String text = new String(data, charset);
                String foundColor = findSection(text, "[ColorScheme.");
                String foundSize = findSection(text, "[Size.");
                if (foundColor != null || foundSize != null) {
                    if (foundColor != null) color = foundColor;
                    if (foundSize != null) size = foundSize;
                    break;
                }
            }
        }
        catch (IOException e) {}
        return new String[]{color, size};
    }

    public static Map<String, String> getSysColors(Context context, String name) {
        Map<String, String> colors = new HashMap<>();
        try {
            byte[] data = Files.readAllBytes(getFile(context, name).toPath());
            for (Charset charset : new Charset[]{StandardCharsets.UTF_16LE, StandardCharsets.ISO_8859_1}) {
                String text = new String(data, charset);
                int start = text.indexOf("[SysMetrics]");
                if (start == -1) continue;

                int end = text.indexOf("\n[", start + 1);
                for (String line : text.substring(start, end != -1 ? end : text.length()).split("\r?\n")) {
                    int comment = line.indexOf(';');
                    if (comment != -1) line = line.substring(0, comment);
                    int equals = line.indexOf('=');
                    if (equals == -1) continue;

                    String value = line.substring(equals + 1).trim();
                    if (value.matches("\\d+ \\d+ \\d+")) colors.put(line.substring(0, equals).trim(), value);
                }
                break;
            }
        }
        catch (IOException e) {}
        return colors;
    }

    private static String findSection(String text, String prefix) {
        int index = 0;
        while ((index = text.indexOf(prefix, index)) != -1) {
            int start = index + prefix.length();
            int end = text.indexOf(']', start);
            boolean commented = index > 0 && text.charAt(index - 1) == ';';
            if (!commented && end != -1 && end - start < 40) return text.substring(start, end);
            index = start;
        }
        return null;
    }

    public static boolean delete(Context context, String name) {
        return FileUtils.delete(getFile(context, name));
    }

    private static boolean isPeImage(File file) {
        try (InputStream is = new FileInputStream(file)) {
            return is.read() == 'M' && is.read() == 'Z';
        }
        catch (java.io.IOException e) {
            return false;
        }
    }
}
