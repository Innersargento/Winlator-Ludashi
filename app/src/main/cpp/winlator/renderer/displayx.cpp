#include <sys/socket.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/un.h>
#include <string.h>
#include <thread>
#include <mutex>

#include <android/api-level.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include "displayx.hpp"

using PFNASURFACETRANSACTIONSETPOSITION = void (*)(ASurfaceTransaction*, ASurfaceControl*, int32_t, int32_t);
using PFNASURFACETRANSACTIONSETBUFFER = void (*)(ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*, int);
using PFNASURFACETRANSACTIONSETGEOMETRY = void (*)(ASurfaceTransaction*, ASurfaceControl*, const ARect&, const ARect&, int32_t);
using PFNASURFACETRANSACTIONSETZORDER = void (*)(ASurfaceTransaction*, ASurfaceControl*, int32_t);
using PFNASURFACETRANSACTIONSETVISIBILITY = void (*)(ASurfaceTransaction*, ASurfaceControl*, enum ASurfaceTransactionVisibility);
using PFNASURFACETRANSACTIONREPARENT = void (*)(ASurfaceTransaction*, ASurfaceControl*, ASurfaceControl*);
using PFNASURFACETRANSACTIONCREATE = ASurfaceTransaction* (*)();
using PFNASURFACETRANSACTIONDELETE = void (*)(ASurfaceTransaction*);
using PFNASURFACETRANSACTIONAPPLY = void (*)(ASurfaceTransaction*);

using PFNASURFACECONTROLACQUIRE = void (*)(ASurfaceControl*);
using PFNASURFACECONTROLRELEASE = void (*)(ASurfaceControl*);
using PFNASURFACECONTROLCREATE = ASurfaceControl* (*)(ASurfaceControl*, const char*);
using PFNASURFACECONTROLCREATEFROMWINDOW = ASurfaceControl* (*)(ANativeWindow*, const char*);

using PFNACHOREOGRAPHERGETINSTANCE = AChoreographer* (*)();
using PFNACHOREOGRAPHERPOSTFRAMECALLBACK64 = void (*)(AChoreographer*, AChoreographer_frameCallback64, void*);

static PFNASURFACETRANSACTIONSETPOSITION pfnASurfaceTransactionSetPosition = nullptr;
static PFNASURFACETRANSACTIONSETBUFFER pfnASurfaceTransactionSetBuffer = nullptr;
static PFNASURFACETRANSACTIONSETGEOMETRY pfnASurfaceTransactionSetGeometry = nullptr;
static PFNASURFACETRANSACTIONSETZORDER pfnASurfaceTransactionSetZOrder = nullptr;
static PFNASURFACETRANSACTIONSETVISIBILITY pfnASurfaceTransactionSetVisibility = nullptr;
static PFNASURFACETRANSACTIONREPARENT pfnASurfaceTransactionReparent = nullptr;
static PFNASURFACETRANSACTIONCREATE pfnASurfaceTransactionCreate = nullptr;
static PFNASURFACETRANSACTIONDELETE pfnASurfaceTransactionDelete = nullptr;
static PFNASURFACETRANSACTIONAPPLY pfnASurfaceTransactionApply = nullptr;

static PFNASURFACECONTROLACQUIRE pfnASurfaceControlAcquire = nullptr;
static PFNASURFACECONTROLRELEASE pfnASurfaceControlRelease = nullptr;
static PFNASURFACECONTROLCREATE pfnASurfaceControlCreate = nullptr;
static PFNASURFACECONTROLCREATEFROMWINDOW pfnASurfaceControlCreateFromWindow = nullptr;

static PFNACHOREOGRAPHERGETINSTANCE pfnAChoreographerGetInstance = nullptr;
static PFNACHOREOGRAPHERPOSTFRAMECALLBACK64 pfnAChoreographerPostFrameCallback64 = nullptr;

void DisplayX::onFrameCallback64(int64_t frameTimeNanos, void* data) {
    auto *self = reinterpret_cast<DisplayX *>(data);
   
    if (!self->env) {
        self->env = self->cache->getEnv();
    }

    if (self->cursorUpdate && self->cursorManager->control && !self->paused) {
        self->updateCursorPosition();
        auto lock = self->displayxLock.lock();
        self->cursorUpdate = false;
    }
    
    auto lock = self->displayxLock.lock();
    self->state = State::REQUEST_WINDOW_UPDATE;
    self->displayxLock.notify();
    lock.unlock();
    
    pfnAChoreographerPostFrameCallback64(self->choreographer, DisplayX::onFrameCallback64, self);
}

void DisplayX::renderingThreadLoop() {
    bool hasSurface = false;
    bool surfaceChanged = false;
    bool restoreState = false;
    
    std::vector<Window *> windows;
    
    while (true) {
        std::function<void()> func = nullptr;
        
        auto lock = displayxLock.lock();
        displayxLock.wait(lock, [&]{ 
            return state != State::NONE || !eventQueue.empty();
        });
        
        auto currState = state;
        
        if (currState == State::STOP) {
            printf("Received state STOP");
            state = State::NONE;
            displayxLock.notify();
            return;
        }
        
        if (currState == State::PAUSE) {
            printf("Received state PAUSE");
            paused = true;
            state = State::NONE;
            displayxLock.notify();
        }
            
        if (currState == State::RESUME) {
            printf("Received state RESUME");
            paused = false;
            restoreState = true;
            state = State::NONE;
            displayxLock.notify();
        }
        
        if (currState == State::CREATE_SURFACE) {
            printf("Received state CREATE_SURFACE");
            createRootWindowControl();
            createRootCursorControl();
            hasSurface = true;
            state = State::NONE;
            displayxLock.notify();
        }
            
        if (currState == State::CHANGE_SURFACE) {
            printf("Received state CHANGE_SURFACE");
            resizeRootWindow();
            surfaceChanged = true;
            state = State::NONE;
            displayxLock.notify();
        }
            
        if (hasSurface && restoreState) {
            printf("Restoring state after resume");
            restoreControlState();
            restoreState = false;
        }
            
        if (currState == State::DESTROY_SURFACE) {
            printf("Received state DESTROY_SURFACE");
            hasSurface = false;
            surfaceChanged = false;
            destroyRootCursorControl();
            destroyRootWindowControl();
            state = State::NONE;
            displayxLock.notify();
        }
        
        if (!eventQueue.empty() && hasSurface && surfaceChanged && !paused) {
            func = eventQueue.front();
            eventQueue.pop();
        }
       
        if (!windowQueue.empty() && hasSurface && surfaceChanged && !paused && !func) {
            state = State::NONE;
            while (!windowQueue.empty()) {
                auto window = windowQueue.pop();
                windows.push_back(window);
            }
        }
        
        lock.unlock();
        
        if (func) {
            func();
        }
        
        for (auto window : windows) {
            if (window) {
                if (window->currentDirectContent) 
                    updateWindowDirect(window);
                else
                    updateWindow(window);
            }
        }     
        
        windows.clear();  
    }
}

void DisplayX::start() {
    void* handle = dlopen("libandroid.so", RTLD_NOW);
    pfnASurfaceTransactionSetPosition = reinterpret_cast<PFNASURFACETRANSACTIONSETPOSITION>(dlsym(handle,"ASurfaceTransaction_setPosition"));
    pfnASurfaceTransactionSetBuffer = reinterpret_cast<PFNASURFACETRANSACTIONSETBUFFER>(dlsym(handle,"ASurfaceTransaction_setBuffer"));
    pfnASurfaceTransactionSetGeometry = reinterpret_cast<PFNASURFACETRANSACTIONSETGEOMETRY>(dlsym(handle,"ASurfaceTransaction_setGeometry"));
    pfnASurfaceTransactionSetZOrder = reinterpret_cast<PFNASURFACETRANSACTIONSETZORDER>(dlsym(handle,"ASurfaceTransaction_setZOrder"));
    pfnASurfaceTransactionSetVisibility = reinterpret_cast<PFNASURFACETRANSACTIONSETVISIBILITY>(dlsym(handle,"ASurfaceTransaction_setVisibility"));
    pfnASurfaceTransactionReparent = reinterpret_cast<PFNASURFACETRANSACTIONREPARENT>(dlsym(handle,"ASurfaceTransaction_reparent"));
    pfnASurfaceTransactionCreate = reinterpret_cast<PFNASURFACETRANSACTIONCREATE>(dlsym(handle,"ASurfaceTransaction_create"));
    pfnASurfaceTransactionDelete = reinterpret_cast<PFNASURFACETRANSACTIONDELETE>(dlsym(handle,"ASurfaceTransaction_delete"));
    pfnASurfaceTransactionApply = reinterpret_cast<PFNASURFACETRANSACTIONAPPLY>(dlsym(handle,"ASurfaceTransaction_apply"));

    pfnASurfaceControlAcquire = reinterpret_cast<PFNASURFACECONTROLACQUIRE>(dlsym(handle,"ASurfaceControl_acquire"));
    pfnASurfaceControlRelease = reinterpret_cast<PFNASURFACECONTROLRELEASE>(dlsym(handle,"ASurfaceControl_release"));
    pfnASurfaceControlCreate = reinterpret_cast<PFNASURFACECONTROLCREATE>(dlsym(handle,"ASurfaceControl_create"));
    pfnASurfaceControlCreateFromWindow = reinterpret_cast<PFNASURFACECONTROLCREATEFROMWINDOW>(dlsym(handle,"ASurfaceControl_createFromWindow"));

    pfnAChoreographerGetInstance = reinterpret_cast<PFNACHOREOGRAPHERGETINSTANCE>(dlsym(handle,"AChoreographer_getInstance"));
    pfnAChoreographerPostFrameCallback64 = reinterpret_cast<PFNACHOREOGRAPHERPOSTFRAMECALLBACK64>(dlsym(handle,"AChoreographer_postFrameCallback64"));
        
   displayxThread = std::thread(&DisplayX::renderingThreadLoop, this);
   this->choreographer = pfnAChoreographerGetInstance();
   pfnAChoreographerPostFrameCallback64(this->choreographer, DisplayX::onFrameCallback64, this);
}

void DisplayX::stop() {
    auto lock = displayxLock.lock();
    state = State::STOP;
    displayxLock.notify();
    displayxLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::pause() {
    auto lock = displayxLock.lock();
    state = State::PAUSE;
    displayxLock.notify();
    displayxLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::resume() {
    auto lock = displayxLock.lock();
    state = State::RESUME;
    displayxLock.notify();
    displayxLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::createSurface(ANativeWindow *window) {
    auto lock = displayxLock.lock();
    this->native_window = window;
    state = State::CREATE_SURFACE;
    displayxLock.notify();
    displayxLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::changeSurface(int width, int height) {
    auto lock = displayxLock.lock();
    this->surfaceWidth = width;
    this->surfaceHeight = height;
    state = State::CHANGE_SURFACE;
    displayxLock.notify();
    displayxLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::destroySurface() {
    auto lock = displayxLock.lock();
    this->native_window = nullptr;
    state = State::DESTROY_SURFACE;
    displayxLock.notify();
    displayxLock.wait(lock, [&]{ return state == State::NONE; });
}

void DisplayX::queueEvent(std::function<void()> func) {
    auto lock = displayxLock.lock();
    eventQueue.push(func);
    displayxLock.notify();
}

void DisplayX::requestWindowUpdate(Window *window) {
    auto lock = displayxLock.lock();
    auto result = windowQueue.push(window);
}

void DisplayX::requestCursorUpdate() {
    if (!cursorVisible) return;
    
    auto lock = displayxLock.lock();
    this->cursorUpdate = true;
}

void DisplayX::createWindowControl(Window *window) {
    if (!window->parent || !window->inputOutput) return;
    
    int ret;
    
    AHardwareBuffer_Desc desc{};
    desc.width = window->width;
    desc.height = window->height;
    desc.format = HAL_PIXEL_FORMAT_BGRA_8888;
    desc.layers = 1;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                 
    ret = AHardwareBuffer_allocate(&desc, &window->drawable->ahb);
    if (ret != 0)
        return; 
    
    AHardwareBuffer_acquire(window->drawable->ahb);
    
    AHardwareBuffer_Desc outDesc{};
    AHardwareBuffer_describe(window->drawable->ahb, &outDesc);
    window->drawable->stride = outDesc.stride;
        
    window->control = pfnASurfaceControlCreate(window->parent->control, "displayx");
    if (pfnASurfaceControlAcquire)    
        pfnASurfaceControlAcquire(window->control);
    
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    
    if (pfnASurfaceTransactionSetPosition) {
        pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
    }
    else {
        ARect src{};
        ARect dst = {
            .left = window->x,
            .top = window->y,
            .right = window->x + window->width,
            .bottom = window->y + window->height
        };
        pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, src, dst, 0);
    }  
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::destroyWindowControl(Window *window) {
    if (!window) return;
    
    if (window->drawable && window->drawable->ahb && !window->drawable->isDirectContent) {
        AHardwareBuffer_release(window->drawable->ahb);
        window->drawable->ahb = nullptr;
    }
    
    if (!window->control) return;
 
    pfnASurfaceControlRelease(window->control);
    window->control = nullptr;
}

void DisplayX::mapWindow(Window *window) {
    if (!window->control) return;
    
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::unmapWindow(Window *window) {
    if (!window->control) return;
    
    pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::changeGeometry(Window *window, bool resized) {
    if (!window->control) return;
    
    int ret;
    
    if (resized && !window->drawable->isDirectContent) {
        AHardwareBuffer_release(window->drawable->ahb);
        window->drawable->ahb = nullptr;
        
        AHardwareBuffer_Desc desc{};
        desc.width = window->width;
        desc.height = window->height;
        desc.format = HAL_PIXEL_FORMAT_BGRA_8888;
        desc.layers = 1;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                 
        ret = AHardwareBuffer_allocate(&desc, &window->drawable->ahb);
        if (ret != 0) 
            return;
        
        AHardwareBuffer_acquire(window->drawable->ahb);
        
        AHardwareBuffer_Desc outDesc{};
        AHardwareBuffer_describe(window->drawable->ahb, &outDesc);
        window->drawable->stride = outDesc.stride;
        
        window->drawable->sizeChanged = false;
        pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, window->drawable->ahb, -1);
    }
    
    if (pfnASurfaceTransactionSetPosition) {
        pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
    }
    else {
        ARect src{};
        ARect dst = {
            .left = window->x,
            .top = window->y,
            .right = window->x + window->width,
            .bottom = window->y + window->height
        };
        pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, src, dst, 0);
    }
    
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::updateWindow(Window *window) {
    if (!window->enabled) return;
    
    uint8_t *dst;
    int ret;
    uint8_t *src;
    
    ret = AHardwareBuffer_lock(window->drawable->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | 
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, reinterpret_cast<void **>(&dst));
    if (ret != 0)    
        return;
        
    src = reinterpret_cast<uint8_t *>(window->drawable->data);
    
    for (int y = 0; y < window->drawable->height; ++y) {
        memcpy(dst + y * window->drawable->stride * 4, src + y * window->drawable->width * 4, window->drawable->width * 4);
    }
    
    AHardwareBuffer_unlock(window->drawable->ahb, nullptr);
    
    window->drawable->isDirty = false;
    
    if (pfnASurfaceTransactionSetPosition) {
        pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
    }
    else {
        ARect srcRect{};
        ARect dstRect = {
            .left = window->x,
            .top = window->y,
            .right = window->x + window->width,
            .bottom = window->y + window->height
        };
        pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, srcRect, dstRect, 0);
    }
    
    pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, window->drawable->ahb, -1);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::updateWindowDirect(Window *window) {
    if (!window->enabled) return;
    
    auto drawable = window->currentDirectContent;
    if (!drawable) return;
    
    if (pfnASurfaceTransactionSetPosition) {
        pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
    }
    else { 
        ARect src{};
        ARect dst = {
            .left = window->x,
            .top = window->y,
            .right = window->x + window->width,
            .bottom = window->y + window->height
        };
        pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, src, dst, 0);
    }
         
    pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, drawable->ahb, -1);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::createCursor(Cursor *cursor) {
    int ret;
    
    AHardwareBuffer_Desc desc{};
    desc.width = cursor->image->width;
    desc.height = cursor->image->height;
    desc.format = HAL_PIXEL_FORMAT_BGRA_8888;
    desc.layers = 1;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                 
    ret = AHardwareBuffer_allocate(&desc, &cursor->image->ahb);
    if (ret != 0) 
        return;
    
    AHardwareBuffer_acquire(cursor->image->ahb);
        
    AHardwareBuffer_Desc outDesc{};
    AHardwareBuffer_describe(cursor->image->ahb, &outDesc);
    cursor->image->stride = outDesc.stride;
}

void DisplayX::destroyCursor(Cursor *cursor) {
    if (!cursor || !cursor->image || !cursor->image->ahb) return;
    
    AHardwareBuffer_release(cursor->image->ahb);
    cursor->image->ahb = nullptr;
}

void DisplayX::updateCursor(Window *window) {
    int ret;
    
    auto cursor = window->cursor;
    if (!cursor) return;
    
    uint8_t *dst, *src;
        
    ret = AHardwareBuffer_lock(cursor->image->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | 
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, reinterpret_cast<void **>(&dst));
    if (ret != 0)    
        return;
        
    src = reinterpret_cast<uint8_t *>(cursor->image->data);
    
    for (int y = 0; y < cursor->image->height; ++y) {
        memcpy(dst + y * cursor->image->stride * 4, src + y * cursor->image->width * 4, cursor->image->width * 4);
    }
    
    AHardwareBuffer_unlock(cursor->image->ahb, nullptr);
    
    cursor->image->isDirty = false;
    
    pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, cursor->image->ahb, -1);
    pfnASurfaceTransactionSetVisibility(cursorTransaction, cursorManager->control, (cursor->visible && cursorVisible) ?  ASURFACE_TRANSACTION_VISIBILITY_SHOW : ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    pfnASurfaceTransactionApply(cursorTransaction);
}

void DisplayX::updateCursorPosition() {
    if (!cursorManager->control) return;
    
    jobject pointWindowObj = env->CallObjectMethod(xServer->inputDeviceManager, cache->getPointWindow);
    jint id = env->GetIntField(pointWindowObj, cache->windowID);
    auto pointWindow = windowManager->getWindow(id);
    auto cursor = (pointWindow != nullptr) ? pointWindow->cursor : nullptr;
    auto rootCursor = cursorManager->getRootCursor();
    int x = std::clamp(cursorManager->pointer.posX, 0, windowManager->getRootWindow()->width - 1);
    int y = std::clamp(cursorManager->pointer.posY, 0, windowManager->getRootWindow()->height - 1);
    
    if (cursorVisible || (cursor && cursor->visible)) {
        if (repostCursor) {
            if (cursor != nullptr) {
                pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, cursor->image->ahb, -1);
            }
            else {
                pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, rootCursor->image->ahb, -1);
            }
            auto lock = displayxLock.lock();
            repostCursor = false;
        }
        
        if (pfnASurfaceTransactionSetPosition) {
            pfnASurfaceTransactionSetPosition(cursorTransaction, cursorManager->control, x, y);
        }
        else {
            auto& cursorImage = (cursor != nullptr) ? cursor->image : rootCursor->image;
            ARect src{};
            ARect dst = {
                .left = x,
                .top = y,
                .right = x + cursorImage->width,
                .bottom = y + cursorImage->height
            };
            pfnASurfaceTransactionSetGeometry(cursorTransaction, cursorManager->control, src, dst, 0);
        }
        
        pfnASurfaceTransactionApply(cursorTransaction);
    }
    else {
        pfnASurfaceTransactionSetVisibility(cursorTransaction, cursorManager->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        pfnASurfaceTransactionApply(cursorTransaction);
    }
}

void DisplayX::createRootCursorControl() {
    int ret;
    
    auto rootCursor = cursorManager->getRootCursor();
    auto rootWindow = windowManager->getRootWindow();
    if (!rootCursor || !rootWindow) return;
    
    cursorManager->control = pfnASurfaceControlCreate(rootWindow->control, "displayx");
    if (pfnASurfaceControlAcquire)
        pfnASurfaceControlAcquire(cursorManager->control);
    
    AHardwareBuffer_Desc desc{};
    desc.width = rootCursor->image->width;
    desc.height = rootCursor->image->height;
    desc.format = HAL_PIXEL_FORMAT_BGRA_8888;
    desc.layers = 1;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                 
    ret = AHardwareBuffer_allocate(&desc, &rootCursor->image->ahb);
    if (ret != 0)
        return; 
    
    AHardwareBuffer_acquire(rootCursor->image->ahb);
        
    AHardwareBuffer_Desc outDesc{};
    AHardwareBuffer_describe(rootCursor->image->ahb, &outDesc);
    rootCursor->image->stride = outDesc.stride;     
        
    uint8_t *dst, *src;
        
    ret = AHardwareBuffer_lock(rootCursor->image->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | 
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, reinterpret_cast<void **>(&dst));
    if (ret != 0)    
        return;
        
    src = reinterpret_cast<uint8_t *>(rootCursor->image->data);
    
    for (int y = 0; y < rootCursor->image->height; ++y) {
        memcpy(dst + y * rootCursor->image->stride * 4, src + y * rootCursor->image->width * 4, rootCursor->image->width * 4);
    }
    
    AHardwareBuffer_unlock(rootCursor->image->ahb, nullptr);
    
    rootCursor->image->isDirty = false;
    
    cursorTransaction = pfnASurfaceTransactionCreate();
}

void DisplayX::drawRootCursor() {
    if (!cursorVisible) return;
    
    auto rootCursor = cursorManager->getRootCursor();
    if (!cursorManager) return;
    
    pfnASurfaceTransactionSetBuffer(cursorTransaction, cursorManager->control, rootCursor->image->ahb, -1);
    pfnASurfaceTransactionSetZOrder(cursorTransaction, cursorManager->control, INT32_MAX);
    pfnASurfaceTransactionApply(cursorTransaction);
}

void DisplayX::reparentWindow(Window *window, Window *parent) {
    if (!window->control) return;
    
    pfnASurfaceTransactionReparent(windowTransaction, window->control, parent->control);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::createRootWindowControl() {
    int ret;
    
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    
    AHardwareBuffer_Desc desc{};
    desc.width = rootWindow->width;
    desc.height = rootWindow->height;
    desc.format = HAL_PIXEL_FORMAT_BGRA_8888;
    desc.layers = 1;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                 
    ret = AHardwareBuffer_allocate(&desc, &rootWindow->drawable->ahb);
    if (ret != 0)
        return; 

    AHardwareBuffer_acquire(rootWindow->drawable->ahb);
    
    AHardwareBuffer_Desc outDesc{};
    AHardwareBuffer_describe(rootWindow->drawable->ahb, &outDesc);
    rootWindow->drawable->stride = outDesc.stride; 
    
    rootWindow->control = pfnASurfaceControlCreateFromWindow(this->native_window, "displayx");
    if (pfnASurfaceControlAcquire)      
        pfnASurfaceControlAcquire(rootWindow->control);
 
    windowTransaction = pfnASurfaceTransactionCreate();
}

void DisplayX::destroyRootWindowControl() {
    this->native_window = nullptr;
    pfnASurfaceTransactionDelete(windowTransaction);
    
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    
    if (rootWindow->drawable->ahb) {
        AHardwareBuffer_release(rootWindow->drawable->ahb);
        rootWindow->drawable->ahb = nullptr;
    }
    
    if (!rootWindow->control) return;

    pfnASurfaceControlRelease(rootWindow->control);
    rootWindow->control = nullptr;
}

void DisplayX::destroyRootCursorControl() {
    pfnASurfaceTransactionDelete(cursorTransaction);
    
    auto rootCursor = cursorManager->getRootCursor();
    if (!rootCursor) return;
    
    if (rootCursor->image->ahb) {
        AHardwareBuffer_release(rootCursor->image->ahb);
        rootCursor->image->ahb = nullptr;
    }
    
    if (!cursorManager->control) return;

    pfnASurfaceControlRelease(cursorManager->control);
    cursorManager->control = nullptr;
}

void DisplayX::resizeRootWindow() {
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    
    viewTransformation.update(surfaceWidth, surfaceHeight, rootWindow->width, rootWindow->height);
    
    ARect src{};
    ARect dst{};
    
    if (fullscreen) {
        src.left = viewTransformation.viewOffsetX;
        src.top = viewTransformation.viewOffsetY;
        src.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        src.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
        
        dst.left = 0;
        dst.top = 0;
        dst.right = surfaceWidth;
        dst.bottom = surfaceHeight;
    }
    else {
        src.left = 0;
        src.top = 0;
        src.right = surfaceWidth;
        src.bottom = surfaceHeight;
        
        dst.left = viewTransformation.viewOffsetX;
        dst.top = viewTransformation.viewOffsetY;
        dst.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        dst.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
    }
    
    pfnASurfaceTransactionSetGeometry(windowTransaction, rootWindow->control, src, dst, 0);
    pfnASurfaceTransactionSetBuffer(windowTransaction, rootWindow->control, rootWindow->drawable->ahb, -1);
    pfnASurfaceTransactionApply(windowTransaction);
}

void DisplayX::restoreControlState() {
    const auto& windowTree = windowManager->getWindowTree();
    
    for (const auto& entry : windowTree) {
        auto window = entry.second.get();
        if (window == windowManager->getRootWindow()) continue;
        if (!window->control || !window->parent->control) continue;
        
        pfnASurfaceTransactionReparent(windowTransaction, window->control, window->parent->control);
        pfnASurfaceTransactionSetVisibility(windowTransaction, window->control, window->mapped ? ASURFACE_TRANSACTION_VISIBILITY_SHOW : ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        
        if (pfnASurfaceTransactionSetPosition) {
            pfnASurfaceTransactionSetPosition(windowTransaction, window->control, window->x, window->y);
        }
        else {  
            ARect src{};
            ARect dst = {
                .left = window->x,
                .top = window->y,
                .right = window->x + window->width,
                .bottom = window->y + window->height
            };
            pfnASurfaceTransactionSetGeometry(windowTransaction, window->control, src, dst, 0);        
        }
        
        pfnASurfaceTransactionSetBuffer(windowTransaction, window->control, window->drawable->ahb, -1);
        pfnASurfaceTransactionApply(windowTransaction);
    }
    
    repostCursor = true;
    cursorUpdate = true;
}

void DisplayX::toggleFullscreen() {
    auto rootWindow = windowManager->getRootWindow();
    if (!rootWindow) return;
    
    fullscreen = !fullscreen;
    
    ARect src{};
    ARect dst{};
    
    if (fullscreen) {
        src.left = viewTransformation.viewOffsetX;
        src.top = viewTransformation.viewOffsetY;
        src.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        src.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
        
        dst.left = 0;
        dst.top = 0;
        dst.right = surfaceWidth;
        dst.bottom = surfaceHeight;
    }
    else {
        src.left = 0;
        src.top = 0;
        src.right = surfaceWidth;
        src.bottom = surfaceHeight;
        
        dst.left = viewTransformation.viewOffsetX;
        dst.top = viewTransformation.viewOffsetY;
        dst.right = viewTransformation.viewOffsetX + viewTransformation.viewWidth;
        dst.bottom = viewTransformation.viewOffsetY + viewTransformation.viewHeight;
    }
    
    pfnASurfaceTransactionSetGeometry(windowTransaction, rootWindow->control, src, dst, 0);
    pfnASurfaceTransactionApply(windowTransaction);
}
/* This is a mock implementation of the complete DisplayX. I will rewrite this once the infrastructure is finished
class NativeRenderer {
    private:
        struct Image {
            AHardwareBuffer *buf;
            int fence;
        };
        
        ANativeWindow *window;
        ASurfaceControl *control;
        std::atomic<bool> running{false};
        std::mutex mtx;
        std::condition_variable cv;
        std::thread presenter_thread;
        std::queue<std::unique_ptr<struct Image>> imageQueue;
    
    public:
        NativeRenderer() {
        }
        
        void add(AHardwareBuffer *ahb, int fence) {
            std::unique_lock lk(mtx);
            auto image = std::make_unique<struct Image>();
            image->buf = ahb;
            image->fence = fence;
            imageQueue.push(std::move(image));
            cv.notify_one();
        }
        
        void start(ANativeWindow *window) {
            this->window = window;
            this->control = ASurfaceControl_createFromWindow(window, NATIVE_RENDERER_TAG);
            running.store(true);
            presenter_thread = std::thread(&NativeRenderer::present, this);
        }
        
        void stop() {
            running.store(false);
            cv.notify_one();
            presenter_thread.join();
            ANativeWindow_release(window);
        }
        
        void present() {
            while(running.load()) {
                std::unique_lock lk(mtx);
                
                cv.wait(lk, [this]{return !imageQueue.empty() || !running.load();});
                if (!running.load())
                    break;
                    
                auto image = std::move(imageQueue.front());
                imageQueue.pop();
                lk.unlock();
                
                AHardwareBuffer_Desc desc{};
                AHardwareBuffer_describe(image->buf, &desc);
                                    
                ARect src{};
                ARect dst{};
                                    
                src.top = 0;
                src.left = 0;
                src.right = desc.width;
                src.bottom = desc.height;
                dst.right = ANativeWindow_getWidth(window);
                dst.bottom = ANativeWindow_getHeight(window);
                                    
                ASurfaceTransaction *transaction = ASurfaceTransaction_create();
                ASurfaceTransaction_setBuffer(transaction, control, image->buf, image->fence);
                ASurfaceTransaction_setGeometry(transaction, control, src, dst, 0);
                ASurfaceTransaction_apply(transaction);
                ASurfaceTransaction_delete(transaction);
            }
        }
};

class NativeServer {
    private:
        std::thread listening_thread;
        int efd;
        int server_fd;
        NativeRenderer *renderer;
        std::unordered_map<uint8_t, std::vector<AHardwareBuffer *>> clientSwapchains;
        
        enum class OpCode {
            ADD_CLIENT_SWAPCHAIN = 1,
            PRESENT_IMAGE = 2,
            DESTROY_CLIENT_SWAPCHAIN = 3
        };
        
        int readFD(int& socket) {
            std::vector<char> msg_contents(1);
            struct iovec iov{};
            iov.iov_base = msg_contents.data();
            iov.iov_len = msg_contents.size();
            
            std::vector<char> control_buf(CMSG_SPACE(sizeof(int)));
            struct msghdr msg{};
            msg.msg_iov = &iov;
            msg.msg_iovlen = 1;
            msg.msg_control = control_buf.data();
            msg.msg_controllen = control_buf.size();
            
            recvmsg(socket, &msg, MSG_WAITALL);
            
            struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
            int fd = *reinterpret_cast<int*>(CMSG_DATA(cmsg));
            
            return fd;
        }
        
        void createNativeRendererSocket() {
            int res;
            
            server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
            if (server_fd < 0) 
                __android_log_print(ANDROID_LOG_ERROR, NATIVE_RENDERER_TAG, "Failed to create native rendering socket");
                
            struct sockaddr_un addr{};
            addr.sun_family = AF_UNIX;
            memcpy(addr.sun_path + 1, NATIVE_RENDERER_TAG, strlen(NATIVE_RENDERER_TAG));
            addr.sun_path[0] = '\0';
            socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(NATIVE_RENDERER_TAG);
            res = bind(server_fd, (struct sockaddr *)&addr, len);
            if (res < 0)
               __android_log_print(ANDROID_LOG_ERROR, NATIVE_RENDERER_TAG, "Failed to bind native rendering socket");
               
            res = listen(server_fd, 1);
            if (res < 0)
                __android_log_print(ANDROID_LOG_ERROR, NATIVE_RENDERER_TAG, "Failed to listent to native rendering socket");
                
            efd = epoll_create1(0);
            struct epoll_event event{};
            event.data.fd = server_fd;
            event.events = EPOLLIN;
            
            epoll_ctl(efd, EPOLL_CTL_ADD, server_fd, &event);
            listening_thread = std::thread(&NativeServer::startListeningThread, this);
        }
        
        void startListeningThread() {
            std::array<struct epoll_event, 2> events;
            int n;
            while ((n = epoll_wait(efd, events.data(), 2, -1))) {
                for (int i = 0; i < n; i++) {
                    if (events[i].data.fd == server_fd) {
                        if (events[i].events & EPOLLIN) {
                            __android_log_print(ANDROID_LOG_INFO, NATIVE_RENDERER_TAG, "Received new client connection");
                            int client_fd = accept(server_fd, nullptr, nullptr);
                            struct epoll_event event{};
                            event.data.fd = client_fd;
                            event.events = EPOLLIN;
                            epoll_ctl(efd, EPOLL_CTL_ADD, client_fd, &event);
                        }
                    } else {
                        if (events[i].events & (EPOLLERR | EPOLLHUP)) {
                            __android_log_print(ANDROID_LOG_INFO, NATIVE_RENDERER_TAG, "Client has disconnected");
                            epoll_ctl(efd, EPOLL_CTL_DEL, events[i].data.fd, nullptr);
                            close(events[i].data.fd);
                            clientSwapchains.erase(clientSwapchains.begin(), clientSwapchains.end());
                            continue;
                        }
                        if (events[i].events & EPOLLIN) {
                            int request_code;
                            int size = read(events[i].data.fd, &request_code, 4);
                            if (size <= 0)
                                continue;
                            
                            switch (static_cast<OpCode>(request_code)) {
                                case OpCode::ADD_CLIENT_SWAPCHAIN:
                                {
                                    uint8_t id;
                                    uint32_t imageCount;
                                    uint32_t window;
                                    
                                    read(events[i].data.fd, &id, 1);
                                    read(events[i].data.fd, &imageCount, 4);
                                    read(events[i].data.fd, &window, 4);
                                    
                                    __android_log_print(ANDROID_LOG_INFO, NATIVE_RENDERER_TAG, "Received new swapchain from client, id %d images %d", id, imageCount);
                                    
                                    std::vector<AHardwareBuffer *> images(imageCount);
                                    
                                    for (uint32_t j = 0; j < imageCount; j++) {
                                        AHardwareBuffer *buffer;
                                        AHardwareBuffer_recvHandleFromUnixSocket(events[i].data.fd, &buffer);
                                        images[j] = buffer;
                                    }
                                    
                                    clientSwapchains[id] = images;
                                    
                                    break;
                                }    
                                case OpCode::PRESENT_IMAGE:
                                {
                                    uint8_t id;
                                    int index;
                                    
                                    read(events[i].data.fd, &id, 1);
                                    read(events[i].data.fd, &index, 4);
                                    int fence = readFD(events[i].data.fd);
                                    
                                    AHardwareBuffer *image = clientSwapchains[id].at(index);
                                    renderer->add(image, fence);
                                    break;
                                }    
                                case OpCode::DESTROY_CLIENT_SWAPCHAIN: {
                                    uint8_t id;
                                    read(events[i].data.fd, &id, 1);
                                    
                                    clientSwapchains.erase(id);
                                    break;
                                }
                                default:
                                    break;            
                            }
                        }
                    }
                }
            }
        }
        
    public:
        NativeServer() {
        }
        
        void setRenderer(NativeRenderer *renderer) {
            this->renderer = renderer;
        }
        
        void start() {
            createNativeRendererSocket();
        }
};

NativeServer server;
NativeRenderer renderer;
*/