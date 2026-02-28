#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>

#define TAG "NativeHdr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* Function pointer type for ANativeWindow_setBuffersDataSpace (API 28+).
 * We load it dynamically to support minSdk < 28. */
typedef int32_t (*PFN_setBuffersDataSpace)(ANativeWindow*, int32_t);

/**
 * Set the buffer data space on a Surface's native window.
 *
 * Dynamically loads ANativeWindow_setBuffersDataSpace() (public NDK API 28+)
 * via dlsym so the library can be compiled with minSdk 24 while still
 * using the function at runtime on API 28+ devices.
 *
 * SurfaceFlinger reads the data space from each buffer to determine
 * HDR output mode:
 *   BT2020_PQ  (0x09C60000) -> HDR10 output
 *   BT2020_HLG (0x09460000) -> HLG output
 *
 * @param surface Android Surface object
 * @param dataSpace Android DataSpace constant
 * @return 0 on success, -1 on ANativeWindow error, -2 if API not available
 */
JNIEXPORT jint JNICALL
Java_com_pgeneratorplus_android_hdr_HdrEglHelper_nativeSetBuffersDataSpace(
    JNIEnv *env, jobject thiz, jobject surface, jint dataSpace) {

    /* Dynamically resolve the function (API 28+) */
    static PFN_setBuffersDataSpace pfn = NULL;
    static int resolved = 0;
    if (!resolved) {
        resolved = 1;
        /* Try RTLD_DEFAULT first (searches all loaded libraries) */
        pfn = (PFN_setBuffersDataSpace)dlsym(RTLD_DEFAULT,
            "ANativeWindow_setBuffersDataSpace");
        /* If not found, try explicitly loading libnativewindow.so */
        if (!pfn) {
            void *lib = dlopen("libnativewindow.so", RTLD_LAZY);
            if (lib) {
                pfn = (PFN_setBuffersDataSpace)dlsym(lib,
                    "ANativeWindow_setBuffersDataSpace");
                if (pfn) {
                    LOGI("Resolved via dlopen(libnativewindow.so)");
                }
                /* Don't dlclose — keep the library loaded */
            }
        }
        if (pfn) {
            LOGI("ANativeWindow_setBuffersDataSpace resolved");
        } else {
            LOGW("ANativeWindow_setBuffersDataSpace not available (API < 28?)");
        }
    }

    if (!pfn) {
        return -2;
    }

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        LOGW("Failed to get ANativeWindow from Surface");
        return -1;
    }

    int32_t result = pfn(window, (int32_t)dataSpace);
    ANativeWindow_release(window);

    if (result == 0) {
        LOGI("Set buffer data space to 0x%08x via NDK", dataSpace);
    } else {
        LOGW("ANativeWindow_setBuffersDataSpace(0x%08x) failed: %d", dataSpace, result);
    }

    return result;
}
