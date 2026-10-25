package com.winlator.cmod.core;

import android.content.Context;

import java.io.File;

/**
 * Runs executables that live outside nativeLibraryDir (box64, FEX, wine, ld.so, ...).
 * With targetSdk >= 29 execve() on app data files is denied, so everything is started as
 * "libwinloader.so [-a argv0] program args": the loader maps the ELF itself and also
 * intercepts the exec() calls made by the program (see cpp/winlator/loader/winloader.c).
 */
public abstract class ElfLoader {
    public static final String LIBRARY_NAME = "libwinloader.so";

    /** Env var read by the loader: program that receives targets which are not aarch64 ELFs. */
    public static final String ENV_FALLBACK = "WINLOADER_FALLBACK";
    /** Env var read by the loader: disables the exec() interception. */
    public static final String ENV_NO_SECCOMP = "WINLOADER_NOSECCOMP";

    public static String getPath(Context context) {
        return new File(AppUtils.getNativeLibDir(context), LIBRARY_NAME).getPath();
    }

    /** Prefixes a space separated command line with the loader. */
    public static String wrap(Context context, String command) {
        return getPath(context) + " " + command;
    }
}
