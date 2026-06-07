// =============================================================================
// xserver_shim — Legacy GLES2 renderer "translator" for GameHub 6.0.7
// =============================================================================
//
// SKELETON / DRAFT (2026-06-07). Compiles; NOT yet device-validated. The two
// device-only unknowns (does it composite under 6.0.7's single-process model;
// the libwinemu/DirectRendering coupling) are out of scope for this file — this
// only solves the JNI command-surface mismatch documented in
// docs/LEGACY_RENDERER_607_SHIM_RECON.md (Appendix A = the recovered table).
//
// WHAT THIS IS
//   A wrapper `libxserver.so`. The per-game legacy swap loads THIS instead of the
//   raw 6.0.2 libxserver_legacy.so. Its JNI_OnLoad:
//     1. dlopen()s the real legacy lib (a plain dlopen does NOT run its JNI_OnLoad).
//     2. Runs the legacy JNI_OnLoad ourselves, with the global JNINativeInterface
//        table's RegisterNatives temporarily redirected to our hook — so we
//        HARVEST the legacy lib's 11 real C function pointers (by name) and the
//        two methods 6.0.7 deleted are never named against the real class.
//        (Running its JNI_OnLoad preserves any init side-effects it does.)
//     3. Publishes a 40-entry table against the real 6.0.7 XServer class:
//          9 forward (captured ptr, name+sig identical),
//          surfaceChanged forwarded via a wrapper that also injects setSurfaceFormat,
//          setGpuPassthroughEnabled -> drives the captured setRenderingEnabled (forced-on),
//          stop -> best-effort teardown,
//          29 effects* -> stubs.
//
//   Net result: no NoSuchMethodError at load, every command the app can issue is
//   answered, and the legacy GLES2 engine's real functions back the 9 that matter.
//
// CLASS:   com/winemu/core/server/XServer
// LEGACY:  libxserver_legacy.so  (md5 e8eb8948…, 6.0.2, WinEmuKernel4)
// =============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>

#define TAG "BH_XSERVER_SHIM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define XSERVER_CLASS   "com/winemu/core/server/XServer"
#define LEGACY_SONAME   "libxserver_legacy.so"

// 6.0.7 stopped calling setSurfaceFormat(I)V, but the GLES2 engine still needs a
// format set. TODO(device): confirm the correct value — likely the same constant
// 6.0.4 passed (capture it from a stock run, or try the GLES2 default). 0 = placeholder.
#define DEFAULT_SURFACE_FORMAT 0

// ---- captured legacy fn-pointers (harvested from the legacy RegisterNatives) ----
typedef struct { const char* name; void* fn; } cap_t;
static cap_t g_cap[32];
static int   g_cap_n = 0;

static void* get_fn(const char* name) {
    for (int i = 0; i < g_cap_n; i++)
        if (strcmp(g_cap[i].name, name) == 0) return g_cap[i].fn;
    return NULL;
}

// Our hook installed into the global JNI table during the legacy JNI_OnLoad.
// Captures every {name -> fnPtr} the legacy lib tries to register, and returns OK
// WITHOUT registering — so the two 6.0.7-deleted names never hit the real class.
static jint hook_RegisterNatives(JNIEnv* env, jclass clazz,
                                 const JNINativeMethod* methods, jint n) {
    (void)env; (void)clazz;
    for (jint i = 0; i < n && g_cap_n < (int)(sizeof(g_cap)/sizeof(g_cap[0])); i++) {
        g_cap[g_cap_n].name = strdup(methods[i].name);
        g_cap[g_cap_n].fn   = methods[i].fnPtr;
        g_cap_n++;
    }
    LOGI("captured %d legacy native(s)", n);
    return JNI_OK;
}

// Temporarily redirect the live JNINativeInterface table's RegisterNatives slot,
// run the legacy JNI_OnLoad, then restore. NOTE: this mutates the process-wide JNI
// table for the duration of one call on the loading thread (early, single-threaded
// w.r.t. native registration) — acceptable here; revisit if it ever races.
static int capture_legacy(JavaVM* vm) {
    void* h = dlopen(LEGACY_SONAME, RTLD_NOW | RTLD_LOCAL);
    if (!h) { LOGE("dlopen %s failed: %s", LEGACY_SONAME, dlerror()); return -1; }

    jint (*legacy_onload)(JavaVM*, void*) =
        (jint (*)(JavaVM*, void*)) dlsym(h, "JNI_OnLoad");
    if (!legacy_onload) { LOGE("legacy JNI_OnLoad not found"); return -1; }

    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return -1;

    // *env is the function table pointer; cast away const so we can swap one slot.
    struct JNINativeInterface* tbl = (struct JNINativeInterface*) *env;
    long ps = sysconf(_SC_PAGESIZE);
    uintptr_t addr = (uintptr_t) &tbl->RegisterNatives;
    void* page = (void*) (addr & ~(uintptr_t)(ps - 1));
    if (mprotect(page, ps * 2, PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect(RW) on JNI table failed"); return -1;
    }
    jint (*orig)(JNIEnv*, jclass, const JNINativeMethod*, jint) = tbl->RegisterNatives;
    tbl->RegisterNatives = hook_RegisterNatives;

    legacy_onload(vm, NULL);          // runs init + funnels its RegisterNatives to us

    tbl->RegisterNatives = orig;
    mprotect(page, ps * 2, PROT_READ); // best-effort restore
    LOGI("legacy JNI_OnLoad complete; %d native(s) harvested", g_cap_n);
    return 0;
}

// ----------------------------- wrappers / bridges ----------------------------
typedef void (*fn_set_format)(JNIEnv*, jobject, jint);
typedef void (*fn_set_bool)  (JNIEnv*, jobject, jboolean);
typedef void (*fn_surface)   (JNIEnv*, jobject, jobject);

// surfaceChanged: inject the setSurfaceFormat 6.0.7 no longer calls, then forward.
static void w_surfaceChanged(JNIEnv* env, jobject thiz, jobject surface) {
    fn_set_format setFmt = (fn_set_format) get_fn("setSurfaceFormat");
    if (setFmt) setFmt(env, thiz, DEFAULT_SURFACE_FORMAT); // TODO(device): ordering/value
    fn_surface surf = (fn_surface) get_fn("surfaceChanged");
    if (surf) surf(env, thiz, surface);
}

// setGpuPassthroughEnabled(Z): no legacy analog. The GLES2 engine has one master
// switch (setRenderingEnabled) — drive it ON (the proven 6.0.4 "force true" remap).
static void w_setGpuPassthroughEnabled(JNIEnv* env, jobject thiz, jboolean enabled) {
    (void)enabled;
    fn_set_bool re = (fn_set_bool) get_fn("setRenderingEnabled");
    if (re) re(env, thiz, JNI_TRUE);
}

// stop()Z: legacy lib has no stop(); best-effort = disable the renderer, report ok.
static jboolean w_stop(JNIEnv* env, jobject thiz) {
    fn_set_bool re = (fn_set_bool) get_fn("setRenderingEnabled");
    if (re) re(env, thiz, JNI_FALSE);
    return JNI_TRUE;
}

// ------------------------------ effects* stubs -------------------------------
// 6.0.7's ReShade-style post-processing layer. None of it is needed to run a game;
// return empty/false/null/0 so the app's effects UI is inert but never crashes.
static void       fx_applyPreset (JNIEnv*e,jobject t,jstring s){(void)e;(void)t;(void)s;}
static jstring    fx_effectName  (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return NULL;}
static jstring    fx_effectSrc   (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return NULL;}
static jstring    fx_exportPreset(JNIEnv*e,jobject t){(void)e;(void)t;return NULL;}
static jboolean   fx_getTechEn   (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return JNI_FALSE;}
static jboolean   fx_isEnabled   (JNIEnv*e,jobject t){(void)e;(void)t;return JNI_FALSE;}
static jstring    fx_lastError   (JNIEnv*e,jobject t){(void)e;(void)t;return NULL;}
static jlongArray fx_listEffects (JNIEnv*e,jobject t){(void)e;(void)t;return NULL;}
static jlongArray fx_listTechs   (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return NULL;}
static jlongArray fx_listUniforms(JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return NULL;}
static jlong      fx_loadEffect  (JNIEnv*e,jobject t,jstring a,jstring b,jobjectArray c,jobjectArray d){(void)e;(void)t;(void)a;(void)b;(void)c;(void)d;return 0;}
static void       fx_setEnabled  (JNIEnv*e,jobject t,jboolean b){(void)e;(void)t;(void)b;}
static void       fx_setTechEn   (JNIEnv*e,jobject t,jlong h,jboolean b){(void)e;(void)t;(void)h;(void)b;}
static jstring    fx_techName    (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return NULL;}
static jobject    fx_annoBool    (JNIEnv*e,jobject t,jlong h,jstring s){(void)e;(void)t;(void)h;(void)s;return NULL;}
static jobject    fx_annoFloat   (JNIEnv*e,jobject t,jlong h,jstring s){(void)e;(void)t;(void)h;(void)s;return NULL;}
static jobject    fx_annoInt     (JNIEnv*e,jobject t,jlong h,jstring s){(void)e;(void)t;(void)h;(void)s;return NULL;}
static jstring    fx_annoString  (JNIEnv*e,jobject t,jlong h,jstring s){(void)e;(void)t;(void)h;(void)s;return NULL;}
static jbooleanArray fx_getBool  (JNIEnv*e,jobject t,jlong h,jint i){(void)e;(void)t;(void)h;(void)i;return NULL;}
static jfloatArray   fx_getFloat (JNIEnv*e,jobject t,jlong h,jint i){(void)e;(void)t;(void)h;(void)i;return NULL;}
static jintArray     fx_getInt   (JNIEnv*e,jobject t,jlong h,jint i){(void)e;(void)t;(void)h;(void)i;return NULL;}
static jintArray     fx_uniInfo  (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return NULL;}
static jstring    fx_uniName     (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;return NULL;}
static void       fx_uniReset    (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;}
static void       fx_setBool     (JNIEnv*e,jobject t,jlong h,jbooleanArray a){(void)e;(void)t;(void)h;(void)a;}
static void       fx_setFloat    (JNIEnv*e,jobject t,jlong h,jfloatArray a){(void)e;(void)t;(void)h;(void)a;}
static void       fx_setInt      (JNIEnv*e,jobject t,jlong h,jintArray a){(void)e;(void)t;(void)h;(void)a;}
static void       fx_unloadAll   (JNIEnv*e,jobject t){(void)e;(void)t;}
static void       fx_unloadOne   (JNIEnv*e,jobject t,jlong h){(void)e;(void)t;(void)h;}

// ----------------------------- the 40-entry table ----------------------------
// fnPtr == NULL  => filled at runtime from the captured legacy pointer (direct forward).
// fnPtr != NULL  => our wrapper/bridge/stub.
static JNINativeMethod gMethods[] = {
    // 9 forwards (8 direct + surfaceChanged wrapped) — names+sigs match 6.0.2 exactly
    {"startUI",          "()V",                                          NULL},
    {"start",            "(Ljava/lang/String;[Ljava/lang/String;)Z",     NULL},
    {"setShmPath",       "(Ljava/lang/String;)V",                        NULL},
    {"sendWindowChange", "(IIILjava/lang/String;)V",                     NULL},
    {"sendMouseEvent",   "(FFIZZ)V",                                     NULL},
    {"sendTouchEvent",   "(IIII)V",                                      NULL},
    {"sendKeyEvent",     "(IIZ)Z",                                       NULL},
    {"sendTextEvent",    "([B)V",                                        NULL},
    {"surfaceChanged",   "(Landroid/view/Surface;)V",                    (void*)w_surfaceChanged},
    // bridges
    {"setGpuPassthroughEnabled", "(Z)V",                                 (void*)w_setGpuPassthroughEnabled},
    {"stop",             "()Z",                                          (void*)w_stop},
    // 29 effects* stubs
    {"effectsApplyPreset",       "(Ljava/lang/String;)V",                              (void*)fx_applyPreset},
    {"effectsEffectName",        "(J)Ljava/lang/String;",                              (void*)fx_effectName},
    {"effectsEffectSourcePath",  "(J)Ljava/lang/String;",                              (void*)fx_effectSrc},
    {"effectsExportPreset",      "()Ljava/lang/String;",                               (void*)fx_exportPreset},
    {"effectsGetTechniqueEnabled","(J)Z",                                              (void*)fx_getTechEn},
    {"effectsIsEnabled",         "()Z",                                                (void*)fx_isEnabled},
    {"effectsLastError",         "()Ljava/lang/String;",                               (void*)fx_lastError},
    {"effectsListEffects",       "()[J",                                               (void*)fx_listEffects},
    {"effectsListTechniques",    "(J)[J",                                              (void*)fx_listTechs},
    {"effectsListUniforms",      "(J)[J",                                              (void*)fx_listUniforms},
    {"effectsLoadEffect",        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)J", (void*)fx_loadEffect},
    {"effectsSetEnabled",        "(Z)V",                                               (void*)fx_setEnabled},
    {"effectsSetTechniqueEnabled","(JZ)V",                                             (void*)fx_setTechEn},
    {"effectsTechniqueName",     "(J)Ljava/lang/String;",                              (void*)fx_techName},
    {"effectsUniformAnnoBool",   "(JLjava/lang/String;)Ljava/lang/Boolean;",           (void*)fx_annoBool},
    {"effectsUniformAnnoFloat",  "(JLjava/lang/String;)Ljava/lang/Float;",             (void*)fx_annoFloat},
    {"effectsUniformAnnoInt",    "(JLjava/lang/String;)Ljava/lang/Integer;",           (void*)fx_annoInt},
    {"effectsUniformAnnoString", "(JLjava/lang/String;)Ljava/lang/String;",            (void*)fx_annoString},
    {"effectsUniformGetBool",    "(JI)[Z",                                             (void*)fx_getBool},
    {"effectsUniformGetFloat",   "(JI)[F",                                             (void*)fx_getFloat},
    {"effectsUniformGetInt",     "(JI)[I",                                             (void*)fx_getInt},
    {"effectsUniformInfo",       "(J)[I",                                              (void*)fx_uniInfo},
    {"effectsUniformName",       "(J)Ljava/lang/String;",                              (void*)fx_uniName},
    {"effectsUniformReset",      "(J)V",                                               (void*)fx_uniReset},
    {"effectsUniformSetBool",    "(J[Z)V",                                             (void*)fx_setBool},
    {"effectsUniformSetFloat",   "(J[F)V",                                             (void*)fx_setFloat},
    {"effectsUniformSetInt",     "(J[I)V",                                             (void*)fx_setInt},
    {"effectsUnloadAll",         "()V",                                                (void*)fx_unloadAll},
    {"effectsUnloadEffect",      "(J)V",                                               (void*)fx_unloadOne},
};
#define N_METHODS ((jint)(sizeof(gMethods)/sizeof(gMethods[0])))  // == 40

// --------------------------------- entry -------------------------------------
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    if (capture_legacy(vm) != 0) { LOGE("legacy capture failed"); return JNI_ERR; }

    // Fill direct-forward slots from the harvested pointers.
    for (jint i = 0; i < N_METHODS; i++) {
        if (gMethods[i].fnPtr == NULL) {
            void* f = get_fn(gMethods[i].name);
            if (!f) { LOGE("legacy fn missing: %s", gMethods[i].name); return JNI_ERR; }
            gMethods[i].fnPtr = f;
        }
    }

    // FindClass here uses the loadLibrary("xserver") caller's classloader (XServer's),
    // so the app class resolves. (Same context the legacy lib relied on.)
    jclass cls = (*env)->FindClass(env, XSERVER_CLASS);
    if (!cls) { LOGE("class %s not found", XSERVER_CLASS); return JNI_ERR; }

    if ((*env)->RegisterNatives(env, cls, gMethods, N_METHODS) != JNI_OK) {
        LOGE("RegisterNatives(%d) failed", N_METHODS); return JNI_ERR;
    }
    LOGI("registered %d natives (9 forward + 2 bridge + 29 stub) onto %s",
         N_METHODS, XSERVER_CLASS);
    return JNI_VERSION_1_6;
}
