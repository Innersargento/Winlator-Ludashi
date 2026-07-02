#pragma once

#include <mutex>

#include "renderer_jni.hpp"

struct Drawable { 
    int id;
    int width;
    int height;
    int textureId;
    bool isDirty;
    bool sizeChanged;
    void *data;
    jobject drawableObj;
};