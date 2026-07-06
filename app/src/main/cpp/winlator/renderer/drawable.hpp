#pragma once

#include <mutex>

#include "renderer_jni.hpp"

struct Drawable { 
    int id;
    int width;
    int format;
    int height;
    int textureId;
    bool isDirty;
    bool sizeChanged;
    bool isDirectContent;
    void *data;
    jobject drawableObj;
    AHardwareBuffer *ahb;
};