package com.winlator.cmod.core;

import com.winlator.cmod.container.Container;

/**
 * Persists the mouse cursor colors (0xRRGGBB) chosen in the container settings, in the container's extra data.
 * The X server applies them in CursorManager.recolorCursor, the only place Wine's cursor colors pass through.
 */
public abstract class CursorTintStore {
    public static final int DEFAULT_INSIDE = 0xffffff;
    public static final int DEFAULT_OUTLINE = 0x000000;

    private static final String EXTRA_NAME = "cursorColors";

    /** @return {inside, outline} as 0xRRGGBB, the defaults when nothing was saved */
    public static int[] get(Container container) {
        if (container != null) {
            try {
                String[] parts = container.getExtra(EXTRA_NAME).split(",");
                return new int[]{Integer.parseInt(parts[0]) & 0xffffff, Integer.parseInt(parts[1]) & 0xffffff};
            }
            catch (RuntimeException e) {
                // nothing saved or malformed: fall through to the defaults
            }
        }
        return new int[]{DEFAULT_INSIDE, DEFAULT_OUTLINE};
    }

    /** Only updates the container's extra data, the caller saves it with Container.saveData(). */
    public static void set(Container container, int insideRgb, int outlineRgb) {
        boolean isDefault = (insideRgb & 0xffffff) == DEFAULT_INSIDE && (outlineRgb & 0xffffff) == DEFAULT_OUTLINE;
        container.putExtra(EXTRA_NAME, isDefault ? null : (insideRgb & 0xffffff) + "," + (outlineRgb & 0xffffff));
    }
}
