#include "core/paint_state.h"

#include <algorithm>
#include <bit>
#include "native_drawable.h"
#include "core/request_error.h"

namespace ludashi::x11 {
namespace {
constexpr u32 allowed = (1u << (GCLastBit + 1)) - 1;
}

PaintState::PaintState(u32 id, u8 imageDepth)
    : ProtocolObject(ObjectKind::GC, id), depth(imageDepth),
      attributes{GXcopy, 0xffffffff, 0, 1, 0, LineSolid, CapButt, JoinMiter,
                 FillSolid, EvenOddRule, 0, 0, 0, 0, 0, ClipByChildren, 1, 0, 0, 0, 0, 4, ArcPieSlice} {}

void PaintState::UpdateValues(u32 mask, const u32* values) {
    if (mask & ~allowed) throw BadValueError(mask);
    if (mask & GCClipMask) rectangleClip.reset();
    while (mask) {
        const u32 bit = mask & (0u - mask);
        attributes[std::countr_zero(bit)] = *values++;
        mask ^= bit;
    }
}

void PaintState::CopyValues(const PaintState& source, u32 mask) {
    if (mask & ~allowed) throw BadValueError(mask);
    if (depth != source.depth) throw BadMatchError();
    if (mask & GCClipMask) rectangleClip = source.rectangleClip;
    if (mask == allowed) {
        attributes = source.attributes;
        return;
    }
    while (mask) {
        const u32 bit = mask & (0u - mask);
        const auto index = std::countr_zero(bit);
        attributes[index] = source.attributes[index];
        mask ^= bit;
    }
}

void PaintState::SetClipRectangles(s16 x, s16 y, const xRectangle* rectangles, size_t count) {
    auto region = std::make_shared<std::vector<Rect>>();
    region->reserve(count);
    for (size_t index = 0; index < count; ++index) {
        const auto& r = rectangles[index];
        region->push_back({r.x, r.y, s32{r.x} + r.width, s32{r.y} + r.height});
    }
    attributes[std::countr_zero(u32(GCClipXOrigin))] = u32(x);
    attributes[std::countr_zero(u32(GCClipYOrigin))] = u32(y);
    attributes[std::countr_zero(u32(GCClipMask))] = None;
    rectangleClip = std::move(region);
}

void PaintState::PaintPatternRectangle(NativeDrawable& image, const Rect& bounds, NativeDrawable& pattern,
                                       u32 foreground, u32 background) const {
    const Rect clipped = bounds.Intersection({0, 0, image.GetWidth(), image.GetHeight()});
    if (clipped.Empty()) return;
    const u32 style = GetFillStyle();
    const s32 patternWidth = pattern.GetWidth(), patternHeight = pattern.GetHeight();
    std::vector<u32> tile(size_t(patternWidth) * patternHeight);
    pattern.ReadPixels(reinterpret_cast<u8*>(tile.data()), 0, 0, u16(patternWidth), u16(patternHeight), u16(patternWidth));
    const s32 originX = static_cast<s16>(attributes[std::countr_zero(u32(GCTileStipXOrigin))]);
    const s32 originY = static_cast<s16>(attributes[std::countr_zero(u32(GCTileStipYOrigin))]);
    const auto wrap = [](s32 value, s32 size) { value %= size; return value < 0 ? value + size : value; };
    std::vector<u32> row;
    auto fill = [&](const Rect& area) {
        if (area.Empty()) return;
        const u16 width = u16(area.right - area.left);
        row.resize(width);
        for (s32 y = area.top; y < area.bottom; ++y) {
            const u32* source = tile.data() + size_t(wrap(y - originY, patternHeight)) * patternWidth;
            // FillStippled leaves pixels under clear stipple bits untouched.
            if (style == FillStippled)
                image.ReadPixels(reinterpret_cast<u8*>(row.data()), s16(area.left), s16(y), width, 1, width);
            for (s32 x = area.left; x < area.right; ++x) {
                const u32 value = source[wrap(x - originX, patternWidth)];
                u32& pixel = row[size_t(x - area.left)];
                if (style == FillTiled) pixel = value;
                else if (value & 1) pixel = foreground;
                else if (style == FillOpaqueStippled) pixel = background;
            }
            image.WritePixels(reinterpret_cast<const u8*>(row.data()), 0, 0, s16(area.left), s16(y), width, 1, width);
        }
    };
    if (!rectangleClip) { fill(clipped); return; }
    const s32 x = static_cast<s16>(attributes[std::countr_zero(u32(GCClipXOrigin))]);
    const s32 y = static_cast<s16>(attributes[std::countr_zero(u32(GCClipYOrigin))]);
    for (const auto& r : *rectangleClip)
        fill(clipped.Intersection({r.left + x, r.top + y, r.right + x, r.bottom + y}));
}

void PaintState::PaintRectangle(NativeDrawable& image, const Rect& bounds, u32 color) const {
    const Rect clipped = bounds.Intersection({0, 0, image.GetWidth(), image.GetHeight()});
    if (clipped.Empty()) return;
    auto fill = [&](const Rect& area) {
        if (!area.Empty())
            image.FillRect(area.left, area.top, area.right - area.left, area.bottom - area.top, color);
    };
    if (!rectangleClip) { fill(clipped); return; }
    const s32 x = static_cast<s16>(attributes[std::countr_zero(u32(GCClipXOrigin))]);
    const s32 y = static_cast<s16>(attributes[std::countr_zero(u32(GCClipYOrigin))]);
    for (const auto& r : *rectangleClip)
        fill(clipped.Intersection({r.left + x, r.top + y, r.right + x, r.bottom + y}));
}
} // namespace ludashi::x11
