package com.winlator.cmod.xserver;

import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts which presentation path clients actually use, so we can tell whether OpenGL is reaching
 * the screen through the zero-copy DRI3/AHardwareBuffer route or through a CPU image copy.
 *
 * Zink can present either via kopper (VkSwapchain -> ICD WSI -> DRI3 -> AHardwareBuffer) or via the
 * xlib software path (GPU readback -> XShmPutImage). Both are compiled into the shipped libGL, so
 * only a runtime count tells them apart. A steady stream of shmPutImage at display refresh rate
 * means every frame is being copied through the CPU.
 *
 * Counters are sampled once per second; the hot paths only do an atomic increment.
 */
public final class PresentPathStats {
    private static final String TAG = "PresentPath";
    private static final long REPORT_INTERVAL_NS = 1_000_000_000L;

    /**
     * Set to false to compile the counters out of the hot paths. Off by default: this is the
     * instrument that answered where the pixels go -- readback through the CPU, or zero-copy
     * through the AHardwareBuffer swapchain -- so flip it back on to check that a change did
     * not quietly put the readback back. Every call site is guarded by this flag, so with it
     * false nothing is counted and nothing is logged.
     */
    public static final boolean ENABLED = false;

    private static final AtomicLong shmPutImages = new AtomicLong();
    private static final AtomicLong shmBytes = new AtomicLong();
    private static final AtomicLong corePutImages = new AtomicLong();
    private static final AtomicLong coreBytes = new AtomicLong();
    private static final AtomicLong dri3Imports = new AtomicLong();
    private static final AtomicLong presents = new AtomicLong();
    private static final AtomicLong windowStart = new AtomicLong(System.nanoTime());

    private PresentPathStats() {}

    public static void onShmPutImage(int width, int height, int depth) {
        shmPutImages.incrementAndGet();
        shmBytes.addAndGet((long) width * height * bytesPerPixel(depth));
        maybeReport();
    }

    public static void onCorePutImage(int width, int height, int depth) {
        corePutImages.incrementAndGet();
        coreBytes.addAndGet((long) width * height * bytesPerPixel(depth));
        maybeReport();
    }

    public static void onDri3Import() {
        dri3Imports.incrementAndGet();
        maybeReport();
    }

    public static void onPresent() {
        presents.incrementAndGet();
        maybeReport();
    }

    private static int bytesPerPixel(int depth) {
        return depth > 16 ? 4 : (depth > 8 ? 2 : 1);
    }

    private static void maybeReport() {
        long start = windowStart.get();
        long now = System.nanoTime();
        long elapsed = now - start;
        if (elapsed < REPORT_INTERVAL_NS) return;
        // Whoever wins the CAS owns this reporting window; the others just keep counting.
        if (!windowStart.compareAndSet(start, now)) return;

        long shmCount = shmPutImages.getAndSet(0);
        long shmData = shmBytes.getAndSet(0);
        long coreCount = corePutImages.getAndSet(0);
        long coreData = coreBytes.getAndSet(0);
        long dri3Count = dri3Imports.getAndSet(0);
        long presentCount = presents.getAndSet(0);

        if ((shmCount | coreCount | dri3Count | presentCount) == 0) return;

        double seconds = elapsed / 1e9;
        Log.i(TAG, String.format(
                "shmPutImage=%.0f/s (%.1f MB/s)  corePutImage=%.0f/s (%.1f MB/s)  dri3Import=%.0f/s  present=%.0f/s",
                shmCount / seconds, shmData / seconds / (1024 * 1024),
                coreCount / seconds, coreData / seconds / (1024 * 1024),
                dri3Count / seconds, presentCount / seconds));
    }
}
