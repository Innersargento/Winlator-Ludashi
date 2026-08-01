#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <limits.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define LOG_TAG "ShmFence"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/*
 * Server half of the libxshmfence protocol (MIT).  A DRI3 client hands us a
 * shared memory fd with FenceFromFD and both sides then drive the state living
 * in that page: the client resets the fence before it presents and blocks in
 * xshmfence_await() before reusing the buffer, so a fence tracked only in
 * server memory leaves it waiting forever.
 *
 * libxshmfence ships *two* implementations and picks one at build time, and
 * they share no layout and no wakeup mechanism:
 *
 *   xshmfence_futex.c    a single int32_t: 1 triggered, 0 idle, -1 a waiter is
 *                        parked in FUTEX_WAIT.
 *   xshmfence_pthread.c  a process-shared mutex and condvar guarding an int.
 *
 * Implementing only the futex one is silently wrong against the other: its
 * first word is the mutex, whose state on bionic reads 0x2000 once initialised
 * (MUTEX_SHARED_SHIFT is 13), so the compare-and-swap against 0 never takes.
 * Nothing is written, nobody is woken, and the client waits forever while this
 * side reports success -- which is exactly how it presented on device.
 *
 * So pick from the size of the mapping the client allocated, which is the one
 * thing that distinguishes them from out here.
 */

struct fence_futex {
    int32_t v;
};

struct fence_pthread {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int value;
};

/* What map() hands back to Java, so the rest of the calls know which protocol
 * they are speaking without asking again.
 */
struct fence_map {
    void *addr;
    size_t length;
    int is_pthread;
};

static int futex_wake(int32_t *addr) {
    return syscall(SYS_futex, addr, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_ShmFence_map(JNIEnv *env, jclass obj, jint fd) {
    (void)env; (void)obj;

    struct stat st;
    if (fstat(fd, &st) < 0) {
        LOGE("fstat of fence fd %d failed: %s", fd, strerror(errno));
        return 0;
    }

    /* The futex layout is one int32_t; anything larger is the pthread one.
     * Compare against its size rather than a magic number so this keeps
     * working if either struct ever grows.
     */
    int is_pthread = (size_t)st.st_size > sizeof(struct fence_futex);
    size_t length = is_pthread ? sizeof(struct fence_pthread)
                               : sizeof(struct fence_futex);

    if ((size_t)st.st_size < length) {
        LOGE("fence fd %d is %lld bytes, too small for the %s layout (%zu)",
             fd, (long long)st.st_size, is_pthread ? "pthread" : "futex", length);
        return 0;
    }

    void *addr = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) {
        LOGE("mmap of fence fd %d failed: %s", fd, strerror(errno));
        return 0;
    }

    struct fence_map *map = malloc(sizeof(*map));
    if (!map) {
        munmap(addr, length);
        return 0;
    }

    map->addr = addr;
    map->length = length;
    map->is_pthread = is_pthread;

    LOGD("mapped fence fd %d (%lld bytes) at %p as %s", fd,
         (long long)st.st_size, addr, is_pthread ? "pthread" : "futex");

    return (jlong)(uintptr_t)map;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_unmap(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    struct fence_map *map = (struct fence_map *)(uintptr_t)ptr;
    if (!map) return;

    munmap(map->addr, map->length);
    free(map);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_trigger(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    struct fence_map *map = (struct fence_map *)(uintptr_t)ptr;
    if (!map) return;

    if (map->is_pthread) {
        struct fence_pthread *f = map->addr;

        pthread_mutex_lock(&f->mutex);
        f->value = 1;
        pthread_cond_broadcast(&f->cond);
        pthread_mutex_unlock(&f->mutex);
    } else {
        struct fence_futex *f = map->addr;

        /* A negative old value means a waiter is parked and the
         * compare-and-swap did not take, so publish the triggered state and
         * wake it by hand.
         */
        if (__sync_val_compare_and_swap(&f->v, 0, 1) < 0) {
            f->v = 1;
            futex_wake(&f->v);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_reset(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    struct fence_map *map = (struct fence_map *)(uintptr_t)ptr;
    if (!map) return;

    if (map->is_pthread) {
        struct fence_pthread *f = map->addr;

        pthread_mutex_lock(&f->mutex);
        f->value = 0;
        pthread_mutex_unlock(&f->mutex);
    } else {
        struct fence_futex *f = map->addr;

        /* Only 1 -> 0: resetting a fence somebody is waiting on would lose the
         * wakeup, and resetting an already-reset one must stay a no-op.
         */
        __sync_bool_compare_and_swap(&f->v, 1, 0);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_xserver_ShmFence_query(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    struct fence_map *map = (struct fence_map *)(uintptr_t)ptr;
    if (!map) return JNI_FALSE;

    if (map->is_pthread) {
        struct fence_pthread *f = map->addr;
        int value;

        pthread_mutex_lock(&f->mutex);
        value = f->value;
        pthread_mutex_unlock(&f->mutex);

        return value ? JNI_TRUE : JNI_FALSE;
    }

    struct fence_futex *f = map->addr;
    return __sync_val_compare_and_swap(&f->v, 1, 1) == 1 ? JNI_TRUE : JNI_FALSE;
}
