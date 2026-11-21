#include "core/pointer_sprite.h"

#include <algorithm>
#include <atomic>
#include "core/cursor/cursor_glyph.h"
#include "native_drawable.h"
#include "native_screen.h"
#include "core/request_error.h"

namespace ludashi::x11 {
namespace {

uint32_t StraightAlpha(uint32_t pixel) {
    const uint32_t alpha = pixel >> 24;
    if (alpha == 0) return 0;
    if (alpha == 255) return pixel;
    const auto channel = [alpha](uint32_t value) {
        return std::min(255u, (value * 255 + alpha / 2) / alpha);
    };
    return (alpha << 24) | (channel((pixel >> 16) & 255) << 16) |
           (channel((pixel >> 8) & 255) << 8) | channel(pixel & 255);
}

std::atomic<int32_t> tintInside{-1};
std::atomic<int32_t> tintOutline{-1};

uint32_t Luma(uint32_t rgb) {
    return (((rgb >> 16) & 255) * 299 + ((rgb >> 8) & 255) * 587 + (rgb & 255) * 114) / 1000;
}

// Light parts take the inside color and dark parts the outline color; swapped when `flip` is set.
uint32_t TintColor(uint32_t rgb, bool flip) {
    const int32_t inside = tintInside.load(), outline = tintOutline.load();
    if (inside < 0 || outline < 0) return rgb & 0xffffff;
    return static_cast<uint32_t>((Luma(rgb) >= 128) != flip ? inside : outline) & 0xffffff;
}

// The fill is the brightness that dominates the interior (opaque pixels not touching transparency).
// Judging by the rim instead inverts the hand, whose antialiased edge is light. Thin cursors have no interior.
bool FillIsDark(uint32_t width, uint32_t height, const std::vector<uint8_t>& alpha, const std::vector<uint8_t>& luma) {
    size_t light = 0, dark = 0;
    for (uint32_t y = 0; y < height; ++y) for (uint32_t x = 0; x < width; ++x) {
        const size_t index = size_t(y) * width + x;
        if (alpha[index] < 255) continue;
        bool edge = false;
        for (int dy = -1; dy <= 1 && !edge; ++dy) for (int dx = -1; dx <= 1 && !edge; ++dx) {
            const int nx = int(x) + dx, ny = int(y) + dy;
            edge = nx < 0 || ny < 0 || nx >= int(width) || ny >= int(height) ||
                   alpha[size_t(ny) * width + nx] < 255;
        }
        if (edge) continue;
        (luma[index] >= 128 ? light : dark) += 1;
    }
    return dark > light;
}

// Only black, white and gray cursors (Wine's system cursors) are tinted, colored ones keep their look.
void TintArgbPixels(uint32_t width, uint32_t height, uint32_t* pixels, size_t stride) {
    const int32_t inside = tintInside.load(), outline = tintOutline.load();
    if (inside < 0 || outline < 0) return;
    std::vector<uint8_t> alphas(size_t(width) * height), lumas(size_t(width) * height);
    for (uint32_t y = 0; y < height; ++y) for (uint32_t x = 0; x < width; ++x) {
        const uint32_t pixel = pixels[y * stride + x];
        const int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
        if ((pixel >> 24) && std::max({r, g, b}) - std::min({r, g, b}) > 24) return;
        alphas[size_t(y) * width + x] = static_cast<uint8_t>(pixel >> 24);
        lumas[size_t(y) * width + x] = static_cast<uint8_t>(Luma(pixel));
    }
    const bool flip = FillIsDark(width, height, alphas, lumas);
    for (uint32_t y = 0; y < height; ++y) for (uint32_t x = 0; x < width; ++x) {
        auto& pixel = pixels[y * stride + x];
        const uint32_t alpha = pixel >> 24;
        if (!alpha) continue;
        const uint32_t t = flip ? 255 - Luma(pixel) : Luma(pixel);
        uint32_t out = alpha << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            const int in = (inside >> shift) & 255, ou = (outline >> shift) & 255;
            out |= static_cast<uint32_t>(ou + ((in - ou) * static_cast<int>(t) + 127) / 255) << shift;
        }
        pixel = out;
    }
}

} // namespace

void PointerSprite::SetTint(int32_t insideRgb, int32_t outlineRgb) {
    const bool enabled = insideRgb >= 0 && outlineRgb >= 0;
    tintInside = enabled ? (insideRgb & 0xffffff) : -1;
    tintOutline = enabled ? (outlineRgb & 0xffffff) : -1;
}

PointerSprite::PointerSprite(NativeScreen& display, uint32_t id, int32_t hotX, int32_t hotY,
               NativeDrawable& source, NativeDrawable* mask, uint32_t foreground, uint32_t background)
    : ProtocolObject(ObjectKind::PointerSprite, id), display(display), hotX(hotX), hotY(hotY),
      width(source.GetWidth()), height(source.GetHeight()) {
    if (source.GetDepth() != 1 || hotX < 0 || hotY < 0 || hotX >= width || hotY >= height)
        throw BadMatchError();
    if (mask && (mask->GetDepth() != 1 || mask->GetWidth() != width || mask->GetHeight() != height))
        throw BadMatchError();

    const auto* sourceBits = static_cast<const uint32_t*>(source.Map());
    const auto* maskBits = mask ? static_cast<const uint32_t*>(mask->Map()) : nullptr;
    const size_t sourceStride = source.GetStride() / sizeof(uint32_t);
    const size_t maskStride = mask ? mask->GetStride() / sizeof(uint32_t) : 0;
    shape.resize(size_t(width) * height);
    for (size_t y = 0; y < height; ++y) {
        for (size_t x = 0; x < width; ++x) {
            auto& role = shape[y * width + x];
            if (maskBits && !(maskBits[y * maskStride + x] & 1)) {
                role = PixelRole::Transparent;
            } else {
                role = (sourceBits[y * sourceStride + x] & 1) ? PixelRole::Foreground : PixelRole::Background;
            }
        }
    }
    Recolor(foreground, background);
}

PointerSprite::PointerSprite(NativeScreen& display, uint32_t id, uint32_t sourceGlyph, uint32_t maskGlyph,
               uint32_t foreground, uint32_t background, bool hasMask)
    : ProtocolObject(ObjectKind::PointerSprite, id), display(display), glyph(static_cast<int32_t>(sourceGlyph)) {
    const auto& source = GetCursorGlyph(sourceGlyph);
    const auto* mask = hasMask ? &GetCursorGlyph(maskGlyph) : nullptr;
    int32_t left = std::min<int32_t>(source.left, 0);
    int32_t top = std::min<int32_t>(source.top, 0);
    int32_t right = std::max<int32_t>(source.left + source.width, 1);
    int32_t bottom = std::max<int32_t>(source.top + source.height, 1);
    if (mask) {
        left = std::min<int32_t>(left, mask->left);
        top = std::min<int32_t>(top, mask->top);
        right = std::max<int32_t>(right, mask->left + mask->width);
        bottom = std::max<int32_t>(bottom, mask->top + mask->height);
    }
    hotX = -left;
    hotY = -top;
    width = right - left;
    height = bottom - top;
    shape.resize(size_t(width) * height);
    for (int32_t y = 0; y < height; ++y) {
        for (int32_t x = 0; x < width; ++x) {
            auto& role = shape[size_t(y) * width + x];
            if (mask && !mask->Pixel(left + x - mask->left, top + y - mask->top)) {
                role = PixelRole::Transparent;
            } else {
                role = source.Pixel(left + x - source.left, top + y - source.top)
                    ? PixelRole::Foreground : PixelRole::Background;
            }
        }
    }
    Recolor(foreground, background);
}

PointerSprite::PointerSprite(NativeScreen& display, uint32_t id, uint16_t hotX, uint16_t hotY,
               NativeDrawable& source, bool premultiplied)
    : ProtocolObject(ObjectKind::PointerSprite, id), display(display), hotX(hotX), hotY(hotY),
      width(source.GetWidth()), height(source.GetHeight()) {
    if (!width || !height || hotX >= width || hotY >= height) throw BadMatchError();
    auto converted = display.CreateDrawable(width, height, 32);
    auto* pixels = static_cast<uint32_t*>(converted->Map());
    const auto stride = converted->GetStride() / sizeof(uint32_t);
    source.ReadPixels(reinterpret_cast<uint8_t*>(pixels), 0, 0, width, height, stride);
    if (premultiplied) {
        for (size_t y = 0; y < height; ++y)
            for (size_t x = 0; x < width; ++x)
                pixels[y * stride + x] = StraightAlpha(pixels[y * stride + x]);
    }
    TintArgbPixels(width, height, pixels, stride);
    image = std::move(converted);
}

void PointerSprite::Recolor(uint32_t nextForeground, uint32_t nextBackground) {
    if (shape.empty()) return;
    originForeground = nextForeground;
    originBackground = nextBackground;
    bool flip = false;
    if (tintInside.load() >= 0 && tintOutline.load() >= 0) {
        std::vector<uint8_t> alpha(shape.size()), luma(shape.size());
        for (size_t i = 0; i < shape.size(); ++i) {
            alpha[i] = shape[i] == PixelRole::Transparent ? 0 : 255;
            luma[i] = static_cast<uint8_t>(Luma(shape[i] == PixelRole::Foreground ? nextForeground : nextBackground));
        }
        flip = FillIsDark(width, height, alpha, luma);
    }
    nextForeground = TintColor(nextForeground, flip) | 0xff000000;
    nextBackground = TintColor(nextBackground, flip) | 0xff000000;
    if (image && foreground == nextForeground && background == nextBackground) return;

    // Publish a fresh drawable after conversion succeeds. Render snapshots
    // can keep reading the old colors while this cursor is being recolored.
    auto colored = display.CreateDrawable(width, height, 32);
    auto* pixels = static_cast<uint32_t*>(colored->Map());
    const size_t stride = colored->GetStride() / sizeof(uint32_t);
    const uint32_t colors[] = {0, nextBackground, nextForeground};
    for (size_t y = 0; y < height; ++y) {
        for (size_t x = 0; x < width; ++x) {
            pixels[y * stride + x] = colors[static_cast<uint8_t>(shape[y * width + x])];
        }
    }
    image = std::move(colored);
    foreground = nextForeground;
    background = nextBackground;
}

} // namespace ludashi::x11
