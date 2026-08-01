#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "drawable.hpp"
#include "renderer_jni.hpp"
#include "cursor.hpp"

struct Window {
    int id;
    int width;
    int height;
    int x;
    int y;
    std::string className;
    bool mapped;
    bool inputOutput;
    bool hasContent;
    std::unique_ptr<struct Drawable> drawable;
    Window *parent;
    Cursor *cursor;
    bool enabled;
    std::vector<Window *> children;
    jobject attributes;
    jobject windowObj;
    /* Owned by the X request thread, read by a rendering thread. The X client
     * frees a DRI3 pixmap the moment it drops the drawable, which happens while
     * the renderer still has frames in flight for it -- so ownership is shared
     * and the current pointer is handed out by value, never as a raw pointer
     * the caller could outlive. See getDirectContent().
     */
    std::unordered_map<int, std::shared_ptr<struct Drawable>> directContents;
    std::shared_ptr<struct Drawable> currentDirectContent;
    std::mutex directContentMutex;
    ASurfaceControl *control;

    /**
     * The direct content to draw, kept alive for as long as the caller holds
     * the returned pointer. Returns null when the window has none, which is
     * the only safe thing to test: directContents can still hold other buffers
     * after the current one is freed, so a non-empty map says nothing about
     * whether currentDirectContent is there.
     */
    std::shared_ptr<struct Drawable> getDirectContent() {
        std::lock_guard<std::mutex> lock(directContentMutex);
        return currentDirectContent;
    }

    bool hasDirectContents() {
        std::lock_guard<std::mutex> lock(directContentMutex);
        return !directContents.empty();
    }

    int getRootX() {
        int rootX = x;
        auto window = parent;
        while (window != nullptr) {
            rootX += window->x;
            window = window->parent;
        }
        return rootX;
    }
    
    int getRootY() {
        int rootY = y;
        auto window = parent;
        while (window != nullptr) {
            rootY += window->y;
            window = window->parent;
        }
        return rootY;
    }
};

struct WindowLock {
    std::mutex mutex;
   
   std::unique_lock<std::mutex> lock() {
       return std::unique_lock<std::mutex>(mutex);
   }
};

class WindowManager {
    private:
        std::unordered_map<int, std::unique_ptr<struct Window>> windows;
        Window *rootWindow = nullptr;
        std::string unviewableWMClass;
    
    public:
        WindowLock windowLock;
        
        WindowManager() {}
        void changeZOrder(int stackMode, Window *window, Window *sibling);
        void disableAllDescendants(Window *window);
        Window *getWindow(int id);
        void addWindow(int id, std::unique_ptr<struct Window> window);
        void deleteWindow(Window *window);
        Window* getRootWindow();
        void setRootWindow(Window *window); 
        void reparentWindow(Window *window, Window *parent);
        void setUnviewableWMClass(std::string className);
        std::string getUnviewableWMClass();
        std::unordered_map<int, std::unique_ptr<struct Window>>& getWindowTree();
};