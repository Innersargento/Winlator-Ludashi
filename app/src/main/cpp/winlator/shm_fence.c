#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <limits.h>
#include <linux/futex.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define LOG_TAG "ShmFence"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Server half of the libxshmfence protocol (MIT).  A DRI3 client hands us a
 * shared memory fd with FenceFromFD; both sides then drive a single int32_t
 * living in that page:
 *
 *    1  triggered
 *    0  not triggered, nobody waiting
 *   -1  not triggered, a waiter is parked in FUTEX_WAIT
 *
 * Mesa's loader_dri3 resets the fence before it presents and blocks in
 * xshmfence_await() before it reuses the buffer, so a fence that only lives in
 * server memory leaves the client waiting forever.  The state has to be written
 * into the shared page, and the waiter woken through the same futex address.
 */
struct shm_fence {
    int32_t v;
};

static int futex_wake(int32_t *addr) {
    return syscall(SYS_futex, addr, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_ShmFence_map(JNIEnv *env, jclass obj, jint fd) {
    (void)env; (void)obj;

    void *addr = mmap(NULL, sizeof(struct shm_fence), PROT_READ | PROT_WRITE,
                      MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) {
        LOGE("mmap of fence fd %d failed: %s", fd, strerror(errno));
        return 0;
    }

    return (jlong)(uintptr_t)addr;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_unmap(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    if (ptr) munmap((void *)(uintptr_t)ptr, sizeof(struct shm_fence));
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_trigger(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    if (!ptr) return;
    struct shm_fence *fence = (struct shm_fence *)(uintptr_t)ptr;

    /* A negative old value means a waiter is parked and the compare-and-swap
     * did not take, so publish the triggered state and wake it by hand.
     */
    if (__sync_val_compare_and_swap(&fence->v, 0, 1) < 0) {
        fence->v = 1;
        futex_wake(&fence->v);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_reset(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    if (!ptr) return;
    struct shm_fence *fence = (struct shm_fence *)(uintptr_t)ptr;

    /* Only 1 -> 0: resetting a fence somebody is waiting on would lose the
     * wakeup, and resetting an already-reset one must stay a no-op.
     */
    __sync_bool_compare_and_swap(&fence->v, 1, 0);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_xserver_ShmFence_query(JNIEnv *env, jclass obj, jlong ptr) {
    (void)env; (void)obj;

    if (!ptr) return JNI_FALSE;
    struct shm_fence *fence = (struct shm_fence *)(uintptr_t)ptr;

    return __sync_val_compare_and_swap(&fence->v, 1, 1) == 1 ? JNI_TRUE : JNI_FALSE;
}
