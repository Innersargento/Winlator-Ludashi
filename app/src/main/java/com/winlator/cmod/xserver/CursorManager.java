package com.winlator.cmod.xserver;

import android.util.SparseArray;
import android.util.SparseIntArray;

import java.nio.IntBuffer;
import java.util.ArrayList;

public class CursorManager extends XResourceManager {
    public static final int GLYPH_XTERM = 152;
    private static final int HAL_PIXEL_FORMAT_RGBA_8888 = 1;
    private static final int HAL_PIXEL_FORMAT_RGBX_8888 = 2;
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final SparseIntArray glyphCursors = new SparseIntArray();
    private volatile int insideColor = -1;
    private volatile int outlineColor = -1;
    private final DrawableManager drawableManager;
    private ArrayList<OnCursorModificationListener> listeners = new ArrayList<>();
    
    public interface OnCursorModificationListener {
        default void onCreateCursor(Cursor cursor) {}
        default void onFreeCursor(Cursor cursor) {}
    };
    
    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(0, sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        triggerOnCreateCursor(cursor);
        return cursor;
    }

    /* Glyph cursors are not drawn, only their shape is kept so that the
     * cursor of the window under the pointer can be recognized. */
    public void createGlyphCursor(int id, int sourceChar) {
        glyphCursors.put(id, sourceChar);
    }

    public int getGlyphCursorShape(int id) {
        return glyphCursors.get(id, -1);
    }

    public void freeCursor(int id) {
        glyphCursors.delete(id);
        triggerOnFreeResourceListener(cursors.get(id));
        triggerOnFreeCursor(cursors.get(id));
        cursors.remove(id);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.lockBuffer(maskImage.backingAHB).asIntBuffer();
        boolean result = true;
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) {
                result = false;
                break;
            }
        }
        maskImage.unlockBuffer(maskImage.backingAHB);
        return result;
    }

    /** @param insideRgb 0xRRGGBB for the light part of Wine's cursors, @param outlineRgb 0xRRGGBB for the dark part; -1 keeps Wine's colors */
    public void setCursorColors(int insideRgb, int outlineRgb) {
        this.insideColor = insideRgb;
        this.outlineColor = outlineRgb;
    }

    private static int mapColor(byte red, byte green, byte blue, int inside, int outline) {
        int replacement = isLight(red, green, blue) ? inside : outline;
        return replacement != -1 ? replacement : ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
    }

    private static int swapRedBlue(int rgb) {
        return (rgb & 0x00ff00) | ((rgb & 0xff) << 16) | ((rgb >> 16) & 0xff);
    }

    private static boolean isLight(byte red, byte green, byte blue) {
        return ((red & 0xff) * 299 + (green & 0xff) * 587 + (blue & 0xff) * 114) / 1000 >= 128;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        int inside = this.insideColor;
        int outline = this.outlineColor;

        if (inside != -1 || outline != -1) {
            // drawAlphaMaskedBitmap packs colors as BGRA, so R and B are swapped when the surface is RGBA
            int format = cursor.cursorImage.format;
            boolean rgbaSurface = format == HAL_PIXEL_FORMAT_RGBA_8888 || format == HAL_PIXEL_FORMAT_RGBX_8888;

            int fore = mapColor(foreRed, foreGreen, foreBlue, inside, outline);
            int back = mapColor(backRed, backGreen, backBlue, inside, outline);
            if (rgbaSurface) {
                fore = swapRedBlue(fore);
                back = swapRedBlue(back);
            }

            foreRed = (byte)(fore >> 16);
            foreGreen = (byte)(fore >> 8);
            foreBlue = (byte)fore;
            backRed = (byte)(back >> 16);
            backGreen = (byte)(back >> 8);
            backBlue = (byte)back;
        }

        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
        }
    }

    public void addOnCursorModificationListener(OnCursorModificationListener listener) {
        listeners.add(listener);
    }

    private void triggerOnCreateCursor(Cursor cursor) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onCreateCursor(cursor);
        }
    }

    private void triggerOnFreeCursor(Cursor cursor) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onFreeCursor(cursor);
        }
    }
}