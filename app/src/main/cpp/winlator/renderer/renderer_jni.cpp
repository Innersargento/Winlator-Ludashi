#include "egl.hpp"
#include "cursor.hpp"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

JNICache cache;
JNIXServer xserver;
WindowManager windowManager;
CursorManager cursorManager;
EGLRenderer renderer;

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    jint result = vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    
    cache.init(vm, env);
    
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeInit(JNIEnv *env, jobject thiz, jobject context, jobject xServer) {
    jobject windowManagerObj = env->GetObjectField(xServer, cache.windowManager);
    jobject inputDeviceManagerObj = env->GetObjectField(xServer, cache.inputDeviceManager);
    jobject rootWindowObj = env->GetObjectField(windowManagerObj, cache.rootWindow);
    
    auto rootWindow = std::make_unique<struct Window>();
    
    rootWindow->id = env->GetIntField(rootWindowObj, cache.windowID);
    rootWindow->width = env->CallShortMethod(rootWindowObj, cache.windowGetWidth);
    rootWindow->height = env->CallShortMethod(rootWindowObj, cache.windowGetHeight);
    rootWindow->x = env->CallShortMethod(rootWindowObj, cache.windowGetX);
    rootWindow->y = env->CallShortMethod(rootWindowObj, cache.windowGetY);
    
    jstring className = (jstring)env->CallObjectMethod(rootWindowObj, cache.windowGetClassName);
    const char *chars = env->GetStringUTFChars(className, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(className, chars);
    rootWindow->className = str;
    
    auto drawable = std::make_unique<struct Drawable>();
    jobject drawableObj = env->CallObjectMethod(rootWindowObj, cache.windowGetContent);
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->textureId = -1;
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->data = nullptr;
    drawable->isDirty = false;
    drawable->isDirectContent = false;
    drawable->sizeChanged = false;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);
    rootWindow->drawable = std::move(drawable);
    
    env->DeleteLocalRef(drawableObj);
    
    rootWindow->cursor = nullptr;
    rootWindow->parent = nullptr;
    rootWindow->mapped = true;
    rootWindow->inputOutput = true;
    
    jobject attributes = env->GetObjectField(rootWindowObj, cache.windowAttributes);
    rootWindow->attributes = env->NewGlobalRef(attributes);
    rootWindow->windowObj = env->NewGlobalRef(rootWindowObj);
    
    env->DeleteLocalRef(rootWindowObj);
    env->DeleteLocalRef(attributes);
    
    windowManager.setRootWindow(rootWindow.get());
    windowManager.addWindow(rootWindow->id, std::move(rootWindow));
    
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssets = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObject = env->CallObjectMethod(context, getAssets);
    AAssetManager *assetManager = AAssetManager_fromJava(env, assetManagerObject);
    
    int w = 0, h = 0, c = 0;
    unsigned char *cursorData = nullptr;

    AAsset *cursorAsset = AAssetManager_open(assetManager, "cursor.png", AASSET_MODE_BUFFER);
    if (cursorAsset) {
        off_t len = AAsset_getLength(cursorAsset);
        const unsigned char *data = static_cast<const unsigned char *>(AAsset_getBuffer(cursorAsset));
        cursorData = stbi_load_from_memory(data, len, &w, &h, &c, 4);
        AAsset_close(cursorAsset);
    } else {
        printf("Failed to open cursor.png asset");
    }
    
    auto cursorDrawable = std::make_unique<struct Drawable>();
    cursorDrawable->id = -1;
    cursorDrawable->textureId = -1;
    cursorDrawable->isDirectContent = false;
    cursorDrawable->format = 5;
    cursorDrawable->width = w;
    cursorDrawable->height = h;
    cursorDrawable->data = cursorData;
    cursorDrawable->isDirty = true;
    cursorDrawable->sizeChanged = false;
    cursorDrawable->drawableObj = nullptr;
    
    auto rootCursor = std::make_unique<struct Cursor>();
    rootCursor->id = cursorDrawable->id;
    rootCursor->image = std::move(cursorDrawable);
    rootCursor->hotspotX = 0;
    rootCursor->hotspotY = 0;
    rootCursor->visible = true;
    rootCursor->cursorObj = nullptr;
    
    cursorManager.setRootCursor(std::move(rootCursor));
    
    xserver.windowManager = env->NewGlobalRef(windowManagerObj);
    xserver.inputDeviceManager = env->NewGlobalRef(inputDeviceManagerObj);
    xserver.xserver = env->NewGlobalRef(xServer);
    
    env->DeleteLocalRef(windowManagerObj);
    env->DeleteLocalRef(inputDeviceManagerObj);
    
    renderer.windowManager = &windowManager;
    renderer.cursorManager = &cursorManager;
    renderer.cache = &cache;
    renderer.xServer = &xserver;
    
    renderer.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateWindow(JNIEnv *env, jobject thiz, jobject windowObj, jint parentId) {
    auto window = std::make_unique<struct Window>();
    window->id = env->GetIntField(windowObj, cache.windowID);
    window->width = env->CallShortMethod(windowObj, cache.windowGetWidth);
    window->height = env->CallShortMethod(windowObj, cache.windowGetHeight);
    window->x = env->CallShortMethod(windowObj, cache.windowGetX);
    window->y = env->CallShortMethod(windowObj, cache.windowGetY);
    
    jstring className = (jstring)env->CallObjectMethod(windowObj, cache.windowGetClassName);
    const char *chars = env->GetStringUTFChars(className, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(className, chars);
    window->className = str;
    
    bool isInputOutput = env->CallBooleanMethod(windowObj, cache.windowIsInputOutput);
    window->inputOutput = isInputOutput;
    window->drawable = nullptr;
    
    if (isInputOutput) {
        auto drawable = std::make_unique<struct Drawable>();
        jobject drawableObj = env->CallObjectMethod(windowObj, cache.windowGetContent);
        drawable->id = env->GetIntField(drawableObj, cache.drawableID);
        drawable->textureId = -1;
        drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
        drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
        drawable->data = nullptr;
        drawable->format = 5;
        drawable->isDirty = false;
        drawable->isDirectContent = false;
        drawable->sizeChanged = false;
        drawable->drawableObj = env->NewGlobalRef(drawableObj);
        window->drawable = std::move(drawable);
        env->DeleteLocalRef(drawableObj);
    }
    
    window->cursor = nullptr;
    window->mapped = false;
    window->parent = nullptr;
    
    jobject attributes = env->GetObjectField(windowObj, cache.windowAttributes);
    window->attributes = env->NewGlobalRef(attributes);
    window->windowObj = env->NewGlobalRef(windowObj);
    
    env->DeleteLocalRef(attributes);
    
    Window *raw = window.get();
    Window *parent = (parentId > -1) ? windowManager.getWindow(parentId) : nullptr;
    raw->parent = parent;

    windowManager.addWindow(raw->id, std::move(window));

    if (parent) {
        renderer.queueEvent([parent, raw]{ parent->children.push_back(raw); });
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeMapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    renderer.queueEvent([window]{
        window->mapped = true;
        renderer.updateScene();
    });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUnmapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    renderer.queueEvent([window]{
        window->mapped = false;
        renderer.updateScene();
    });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeDestroyWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    if (window->inputOutput && window->drawable && window->drawable->textureId > 0) {
        int textureId = window->drawable->textureId;
        renderer.queueEvent([textureId] { renderer.destroyTexture(textureId); });
    }

    renderer.queueEvent([window]{ windowManager.deleteWindow(nullptr, window); });
    renderer.queueEvent([]{ renderer.updateScene(); });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateCursor(JNIEnv *env, jobject thiz, jobject cursorObj) {
    auto drawable = std::make_unique<struct Drawable>();
    jobject drawableObj = env->GetObjectField(cursorObj, cache.cursorImage);
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->data = nullptr;
    drawable->format = 5;
    drawable->isDirectContent = false;
    drawable->isDirty = false;
    drawable->textureId = -1;
    drawable->sizeChanged = false;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);
    
    env->DeleteLocalRef(drawableObj);
    
    auto cursor = std::make_unique<struct Cursor>();
    cursor->id = env->GetIntField(cursorObj, cache.cursorID);
    cursor->image = std::move(drawable);
    cursor->hotspotX = env->GetIntField(cursorObj, cache.cursorHotspotX);
    cursor->hotspotY = env->GetIntField(cursorObj, cache.cursorHotspotY);
    cursor->visible = env->CallBooleanMethod(cursorObj, cache.cursorIsVisible);
    cursor->cursorObj = env->NewGlobalRef(cursorObj);

    cursorManager.addCursor(cursor->id, std::move(cursor));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeFreeCursor(JNIEnv *env, jobject thiz, jint id) {
    auto cursor = cursorManager.getCursor(id);
    if (!cursor) return;

    if (cursor->image->textureId > 0) {
        int textureId = cursor->image->textureId;
        renderer.queueEvent([textureId] { renderer.destroyTexture(textureId); });
    }

    cursorManager.removeCursor(env, cursor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeBindCursor(JNIEnv *env, jobject thiz, jint windowId, jint cursorId, jboolean visible, jobject data) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    auto cursor = cursorManager.getCursor(cursorId);
    if (!cursor) return;

    void *addr = env->GetDirectBufferAddress(data);
    bool vis = visible;

    renderer.queueEvent([window, cursor, addr, vis]{
        if (cursor->image->data == nullptr)
            cursor->image->data = addr;

        cursor->visible = vis;
        cursor->image->isDirty = true;

        window->cursor = cursor;
        for (auto &child : window->children)
            child->cursor = cursor;
    });

    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativePointerMove(JNIEnv *env, jobject thiz, jint posX, jint posY) {
    renderer.queueEvent([posX, posY]{
        cursorManager.pointer.posX = posX;
        cursorManager.pointer.posY = posY;
    });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeChangeWindowZOrder(JNIEnv *env, jobject thiz, jint stackMode, jint id, jint siblingId) {
    auto window = windowManager.getWindow(id);
    auto sibling = windowManager.getWindow(siblingId);

    if (!window) return;

    renderer.queueEvent([stackMode, window, sibling]{ windowManager.changeZOrder(stackMode, window, sibling); });
    renderer.queueEvent([]{ renderer.updateScene(); });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateWindowGeometry(JNIEnv *env, jobject thiz, jint id, jint width, jint height, jint x, jint y, jboolean resized) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    renderer.queueEvent([window, width, height, x, y, resized]{
        window->width = width;
        window->height = height;
        window->x = x;
        window->y = y;

        if (resized && window->inputOutput) {
            window->drawable->data = nullptr;
            window->drawable->width = width;
            window->drawable->height = height;
            window->drawable->sizeChanged = true;
        }
    });

    if (resized) {
        renderer.queueEvent([]{ renderer.updateScene(); });
    } else {
        renderer.queueEvent([window]{ renderer.updateWindowPosition(window); });
        renderer.queueEvent([]{ renderer.updateScene(); });
    }
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateWindowContent(JNIEnv *env, jobject thiz, jint id, jobject data) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    void *addr = env->GetDirectBufferAddress(data);

    renderer.queueEvent([window, addr]{
        if (window->drawable->data == nullptr)
            window->drawable->data = addr;

        window->drawable->isDirty = true;
    });
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeReparentWindow(JNIEnv *env, jobject thiz, jint id, jint parentId) {
    auto window = windowManager.getWindow(id);
    auto parent = windowManager.getWindow(parentId);

    if (!window || !parent) return;

    renderer.queueEvent([window, parent]{ windowManager.reparentWindow(window, parent); });
    renderer.queueEvent([]{ renderer.updateScene(); });
    renderer.requestRenderer();
}


extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeToggleFullscreen(JNIEnv *env, jobject thiz) {
    renderer.toggleFullscreen = true;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetCursorVisible(JNIEnv *env, jobject thiz, jboolean visible) {
    renderer.cursorVisible = visible;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetScreenOffsetYRelativeToCursor(JNIEnv *env, jobject thiz, jboolean cond) {
    renderer.screenOffsetYRelativeToCursor = cond;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetMagnifierZoom(JNIEnv *env, jobject thiz, float magnifierZoom) {
    renderer.magnifierZoom = magnifierZoom;
    renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateSurface(JNIEnv *env, jobject thiz, jobject surface) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    renderer.createSurface(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeDestroySurface(JNIEnv *env, jobject thiz) {
    renderer.destroySurface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeChangeSurface(JNIEnv *env, jobject thiz, jint width, jint height) {
    renderer.changeSurface(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativePause(JNIEnv *env, jobject thiz) {
    renderer.pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeResume(JNIEnv *env, jobject thiz) {
    renderer.resume();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeStop(JNIEnv *env, jobject thiz) {
    renderer.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeAddDirectContent(JNIEnv *env, jobject thiz, jint windowId, jobject drawableObj, jobject gpuImageObj) {
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)env->GetLongField(gpuImageObj, cache.gpuImageHardwareBufferPtr);
    if (!hardwareBuffer) return;
    
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    
    auto drawable = std::make_unique<struct Drawable>();
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->textureId = -1;
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->data = nullptr;
    drawable->isDirty = false;
    drawable->format = env->GetIntField(gpuImageObj, cache.gpuImageFormat);
    drawable->sizeChanged = false;
    drawable->ahb = hardwareBuffer;
    drawable->isDirectContent = true;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);

    Drawable *raw = drawable.release();
    int drawableId = raw->id;
    renderer.queueEvent([window, raw, drawableId]{
        window->currentDirectContent = nullptr;
        window->directContents[drawableId] = std::unique_ptr<struct Drawable>(raw);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateDirectContent(JNIEnv *env, jclass obj, jint windowId, jint drawableId) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;

    renderer.queueEvent([window, drawableId]{
        auto it = window->directContents.find(drawableId);
        if (it == window->directContents.end()) return;
        window->currentDirectContent = it->second.get();
    });
    renderer.requestRenderer();
}


extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeRemoveDirectContent(JNIEnv *env, jclass obj, jint windowId, jint drawableId) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;

    renderer.queueEvent([window, drawableId]{
        auto it = window->directContents.find(drawableId);
        if (it == window->directContents.end()) return;
        if (window->currentDirectContent == it->second.get())
            window->currentDirectContent = nullptr;
        window->directContents.erase(it);
    });
    renderer.requestRenderer();
}