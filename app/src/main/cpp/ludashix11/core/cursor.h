#pragma once
#include <vector>

#include "base/types.h"
#include "core/xresource.h"

namespace xorg {

class XScreen;
class Pixmap;
class Drawable;

class Cursor final : public XResource {
public:
    Cursor(XScreen& screen,
           u32 id,
           s32 hotspot_x,
           s32 hotspot_y,
           Pixmap* source_pixmap,
           Pixmap* mask_pixmap,
           u32 fg_color,
           u32 bg_color);

    Cursor(XScreen& screen, u32 id, u32 source_char, u32 mask_char, u32 fg_color, u32 bg_color);
    Cursor(XScreen& screen, u32 id, u16 hotX, u16 hotY, Drawable& image, bool premultiplied = true);

    ~Cursor() override;
    void Recolor(u32 foreground, u32 background);

    // Re-applies the user's cursor colors to a shape cursor created before they were set.
    void Retint() { Recolor(origin_fg, origin_bg); }

    // Replaces the light/dark parts of Wine's black and white cursors; negative disables it.
    static void SetTint(s32 inside_rgb, s32 outline_rgb);

    // Source glyph of an X core font cursor (152 is the I-beam), -1 for any other cursor.
    s32 GetGlyph() const { return glyph; }

    Drawable* GetDrawable() const { return drawable.get(); }

    s32 GetHotspotX() const { return hotspot_x; }

    s32 GetHotspotY() const { return hotspot_y; }

private:
    void UpdateCursorDrawable(u32 width,
                              u32 height,
                              u32 source_stride,
                              const u32* source,
                              u32 mask_stride,
                              const u32* mask,
                              u32 fg_color,
                              u32 bg_color);

    std::vector<u8> shape;
    u32 cursorWidth{}, cursorHeight{};
    XScreen& screen;
    s32 hotspot_x{};
    s32 hotspot_y{};
    sp<Drawable> drawable;
    s32 glyph{-1};
    u32 origin_fg{}, origin_bg{};
};

}  // namespace xorg

