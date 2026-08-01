package com.winlator.cmod.xserver;

/**
 * Server half of the libxshmfence protocol. A DRI3 client passes a shared
 * memory fd with FenceFromFD and then drives the same int32_t from its side:
 * it resets the fence before presenting and blocks in xshmfence_await() before
 * reusing the buffer. A fence tracked only in server memory never wakes it, so
 * every state change has to go through the mapped page.
 */
public abstract class ShmFence {
    static {
        System.loadLibrary("winlator");
    }

    /** Returns 0 when the fd could not be mapped. */
    public static native long map(int fd);

    public static native void unmap(long ptr);

    public static native void trigger(long ptr);

    public static native void reset(long ptr);

    public static native boolean query(long ptr);
}
