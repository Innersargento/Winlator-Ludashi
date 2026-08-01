package com.winlator.cmod.xserver;

public abstract class ShmFence {
    static {
        System.loadLibrary("winlator");
    }

    public static native long map(int fd);

    public static native void unmap(long ptr);

    public static native void trigger(long ptr);

    public static native void reset(long ptr);

    public static native boolean query(long ptr);
}
