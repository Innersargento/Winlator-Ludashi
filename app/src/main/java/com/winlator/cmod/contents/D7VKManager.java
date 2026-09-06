package com.winlator.cmod.contents;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** D7VK WCP selection and Wine's builtin DirectDraw proxy preparation. */
public final class D7VKManager {
    public static final String BUNDLED_VERSION = "2.2";
    public static final String BUNDLED_ASSET = "ddrawrapper/d7vk.tzst";
    private D7VKManager() {}

    public static boolean isD7VK(String selection) {
        return selection != null && (selection.equalsIgnoreCase("d7vk")
                || selection.regionMatches(true, 0, "D7VK-", 0, 5));
    }

    public static List<String> getWrapperEntries(Context context) {
        List<String> entries = new ArrayList<>(Arrays.asList("none", "wined3d", "cnc-ddraw", "dd7to9", "d7vk"));
        ContentsManager manager = new ContentsManager(context);
        manager.syncContents();
        for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK)) {
            entries.add(ContentsManager.getEntryName(profile));
        }
        return entries;
    }

    public static String getWrapperLabel(String entry) {
        return "d7vk".equalsIgnoreCase(entry) ? "D7VK " + BUNDLED_VERSION + " (bundled)" : entry;
    }

    public static String getSignature(String selection) {
        return "d7vk".equalsIgnoreCase(selection) ? ";d7vk-bundled=" + BUNDLED_VERSION : "";
    }

    public static boolean apply(Context context, ContentsManager manager, String selection,
                                File windowsDir, File wineDir, boolean win64, boolean arm64ec) {
        File bundledDir = null;
        try {
            ContentProfile profile;
            File sourceDir;
            if ("d7vk".equalsIgnoreCase(selection)) {
                bundledDir = new File(context.getCacheDir(), "d7vk-bundled");
                FileUtils.delete(bundledDir);
                if (!bundledDir.mkdirs()) return false;
                if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                        BUNDLED_ASSET, bundledDir, null)) return false;
                // Bundled wrappers are plain DLL archives, without WCP metadata.
                ContentProfile.ContentFile dll = new ContentProfile.ContentFile();
                dll.source = "syswow64/ddraw.dll";
                dll.target = "${syswow64}/ddraw.dll";
                profile = new ContentProfile();
                profile.type = ContentProfile.ContentType.CONTENT_TYPE_D7VK;
                profile.fileList = Arrays.asList(dll);
                sourceDir = bundledDir;
            } else {
                profile = manager.getProfileByEntryName(selection);
                if (profile == null || profile.remoteUrl != null) return false;
                sourceDir = ContentsManager.getInstallDir(context, profile);
            }
            if (profile == null || profile.type != ContentProfile.ContentType.CONTENT_TYPE_D7VK
                    || profile.fileList.isEmpty()) return false;

            // Validate all source/proxy files before replacing anything in the prefix.
            List<File[]> copies = new ArrayList<>();
            for (ContentProfile.ContentFile entry : profile.fileList) {
                boolean x86 = "${syswow64}/ddraw.dll".equals(entry.target);
                if (!x86 && !"${system32}/ddraw.dll".equals(entry.target)) return false;
                if (!x86 && !win64) continue;
                File source = new File(sourceDir, entry.source).getCanonicalFile();
                if (!source.toPath().startsWith(sourceDir.getCanonicalFile().toPath()) || !source.isFile()) return false;
                String architecture = x86 ? "i386-windows" : arm64ec ? "aarch64-windows" : "x86_64-windows";
                File builtin = new File(wineDir, "lib/wine/" + architecture + "/ddraw.dll").getCanonicalFile();
                if (!builtin.isFile()) return false;
                File targetDir = new File(windowsDir, x86 && win64 ? "syswow64" : "system32");
                copies.add(new File[]{source, builtin, targetDir});
            }
            if (copies.isEmpty()) return false;
            for (File[] copy : copies) {
                File proxy = new File(copy[2], "ddraw_.dll");
                File target = new File(copy[2], "ddraw.dll");
                if ((proxy.exists() && !proxy.delete()) || (target.exists() && !target.delete())) return false;
                if (!FileUtils.copy(copy[1], proxy) || !proxy.isFile() || proxy.length() != copy[1].length()
                        || !FileUtils.copy(copy[0], target) || !target.isFile() || target.length() != copy[0].length()) return false;
            }
            return true;
        } catch (Exception e) {
            Log.e("D7VK", "Unable to apply " + selection, e);
            return false;
        } finally {
            if (bundledDir != null) FileUtils.delete(bundledDir);
        }
    }
}
