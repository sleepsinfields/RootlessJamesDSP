#include <android/log.h>

#define STEREOEQ_TAG "StereoEQ"

// Always-on logging for Stereo EQ
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  STEREOEQ_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, STEREOEQ_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  STEREOEQ_TAG, __VA_ARGS__)

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"
#include <jni.h>  // if not already included



// ============================================================================
// [RJDSP-LR-EQ] Arbitrary Response EQ – multi-curve subsystem (Master / L / R)
// ============================================================================

// For now this is just a flag layer. Later we can attach separate
// impulse responses / convolvers per curve for real L/R independence.
typedef struct {
    char enabled;    // audio on/off for this curve
} ArbEqCurveFlags;

typedef struct {
    char enabled;           // global subsystem on/off
    ArbEqCurveFlags master; // stereo-linked base curve (L+R)
    ArbEqCurveFlags left;   // extra left-only curve
    ArbEqCurveFlags right;  // extra right-only curve
} ArbEqSubsystemFlags;

// Single instance with defaults: subsystem on, master on, L/R extra off.
static ArbEqSubsystemFlags g_arbEq = {
    .enabled = 1,
    .master = { .enabled = 1 },
    .left   = { .enabled = 0 },
    .right  = { .enabled = 0 },
};
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_JdspNative_setStereoArbEqFlags(
        JNIEnv *env,
        jclass clazz,
        jboolean global,
        jboolean master,
        jboolean left,
        jboolean right)
{
    (void)env;
    (void)clazz;

    g_arbEq.enabled        = (global == JNI_TRUE) ? 1 : 0;
    g_arbEq.master.enabled = (master == JNI_TRUE) ? 1 : 0;
    g_arbEq.left.enabled   = (left   == JNI_TRUE) ? 1 : 0;
    g_arbEq.right.enabled  = (right  == JNI_TRUE) ? 1 : 0;

    LOGE("JNI setStereoArbEqFlags: global=%d master=%d left=%d right=%d",
         g_arbEq.enabled,
         g_arbEq.master.enabled,
         g_arbEq.left.enabled,
         g_arbEq.right.enabled);
}

JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_JdspNative_setStereoArbEqCurves(
        JNIEnv *env,
        jclass clazz,
        jstring masterEq,
        jstring leftEq,
        jstring rightEq)
{
    (void)clazz; // avoid unused warning

    if (masterEq == NULL || (*env)->GetStringUTFLength(env, masterEq) <= 0) {
        LOGE("setStereoArbEqCurves: masterEq is null or empty – ignoring");
        // You might want to disable EQ here or just bail out.
        return;
    }

    const char *masterStr = (*env)->GetStringUTFChars(env, masterEq, NULL);
    const char *leftStr   = NULL;
    const char *rightStr  = NULL;

    jboolean leftIsMaster  = JNI_FALSE;
    jboolean rightIsMaster = JNI_FALSE;

    // Left: use explicit if provided, otherwise fall back to master
    if (leftEq != NULL && (*env)->GetStringUTFLength(env, leftEq) > 0) {
        leftStr = (*env)->GetStringUTFChars(env, leftEq, NULL);
    } else {
        leftStr = masterStr;
        leftIsMaster = JNI_TRUE;
    }

    // Right: use explicit if provided, otherwise fall back to master
    if (rightEq != NULL && (*env)->GetStringUTFLength(env, rightEq) > 0) {
        rightStr = (*env)->GetStringUTFChars(env, rightEq, NULL);
    } else {
        rightStr = masterStr;
        rightIsMaster = JNI_TRUE;
    }

    LOGE("setStereoArbEqCurves JNI: M(len=%d) L(len=%d) R(len=%d)",
         (*env)->GetStringUTFLength(env, masterEq),
         leftEq  ? (*env)->GetStringUTFLength(env, leftEq)  : -1,
         rightEq ? (*env)->GetStringUTFLength(env, rightEq) : -1);

    // TODO: hook into DSP here
    // Example once you know how to get a JamesDSPLib* in this file:
    //
    // ArbitraryResponseEqualizerStringParserStereo(
    //     jdsp,      // <--- your DSP instance pointer
    //     masterStr,
    //     leftStr,
    //     rightStr
    // );
    // ArbitraryResponseEqualizerEnable(jdsp, 1);

    // Clean up JNI strings
    if (!leftIsMaster && leftEq != NULL) {
        (*env)->ReleaseStringUTFChars(env, leftEq, leftStr);
    }
    if (!rightIsMaster && rightEq != NULL) {
        (*env)->ReleaseStringUTFChars(env, rightEq, rightStr);
    }
    (*env)->ReleaseStringUTFChars(env, masterEq, masterStr);
}

void ArbitraryResponseEqualizerConstructor(JamesDSPLib *jdsp)
{
    LOGE("ArbEq ctor: jdsp=%p", (void*)jdsp);

    jdsp->arbMag.instance.filterLen =
        InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, 0);
    LOGE("Constructor called");

    FFTConvolver2x2Init(&jdsp->arbMag.instance.convState);

    // New: three convolvers
    FFTConvolver2x2Init(&jdsp->arbMag.masterConv);
    FFTConvolver2x2Init(&jdsp->arbMag.leftConv);
    FFTConvolver2x2Init(&jdsp->arbMag.rightConv);

    float *kDelta = (float*)malloc(
        jdsp->arbMag.instance.filterLen * sizeof(float));
    memset(kDelta, 0,
           jdsp->arbMag.instance.filterLen * sizeof(float));
    kDelta[0] = 1.0f;

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.instance.convState,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    // Identity IR into all three for now
    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.masterConv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.leftConv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.rightConv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    free(kDelta);
}

void ArbitraryResponseEqualizerDestructor(JamesDSPLib *jdsp)
{
    EqNodesFree(&jdsp->arbMag.instance.coeffGen);
    FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);

    FFTConvolver2x2Free(&jdsp->arbMag.masterConv);
    FFTConvolver2x2Free(&jdsp->arbMag.leftConv);
    FFTConvolver2x2Free(&jdsp->arbMag.rightConv);
}

// ============================================================================
// [RJDSP-LR-EQ] Per-curve setters for Master / Left / Right
// ============================================================================

static void ArbitraryResponseEqualizerSetMasterCurve(
    JamesDSPLib *jdsp,
    const char *stringEq)
{
    // Parse nodes + design FIR using the existing coeffGen
    ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, (char *)stringEq);

    float *eqFil =
        jdsp->arbMag.instance.coeffGen.GetFilter(
            &jdsp->arbMag.instance.coeffGen,
            (float)jdsp->fs
        );

    // Master: EQ on both channels
    FFTConvolver2x2RefreshImpulseResponse(
        &jdsp->arbMag.instance.convState,
        &jdsp->arbMag.masterConv,
        eqFil, eqFil,
        jdsp->arbMag.instance.filterLen
    );
}

static void ArbitraryResponseEqualizerSetLeftCurve(
    JamesDSPLib *jdsp,
    const char *stringEq)
{
    ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, (char *)stringEq);

    float *eqFil =
        jdsp->arbMag.instance.coeffGen.GetFilter(
            &jdsp->arbMag.instance.coeffGen,
            (float)jdsp->fs
        );

    float *kDelta = (float*)malloc(
        jdsp->arbMag.instance.filterLen * sizeof(float));
    memset(kDelta, 0,
           jdsp->arbMag.instance.filterLen * sizeof(float));
    kDelta[0] = 1.0f;

    // Left-only: L = eqFil, R = identity
    FFTConvolver2x2RefreshImpulseResponse(
        &jdsp->arbMag.instance.convState,
        &jdsp->arbMag.leftConv,
        eqFil,  /* L */
        kDelta, /* R */
        jdsp->arbMag.instance.filterLen
    );

    free(kDelta);
}

static void ArbitraryResponseEqualizerSetRightCurve(
    JamesDSPLib *jdsp,
    const char *stringEq)
{
    ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, (char *)stringEq);

    float *eqFil =
        jdsp->arbMag.instance.coeffGen.GetFilter(
            &jdsp->arbMag.instance.coeffGen,
            (float)jdsp->fs
        );

    float *kDelta = (float*)malloc(
        jdsp->arbMag.instance.filterLen * sizeof(float));
    memset(kDelta, 0,
           jdsp->arbMag.instance.filterLen * sizeof(float));
    kDelta[0] = 1.0f;

    // Right-only: L = identity, R = eqFil
    FFTConvolver2x2RefreshImpulseResponse(
        &jdsp->arbMag.instance.convState,
        &jdsp->arbMag.rightConv,
        kDelta, /* L */
        eqFil,  /* R */
        jdsp->arbMag.instance.filterLen
    );

    free(kDelta);
}

// Stereo-aware wrapper: 3 separate strings
void ArbitraryResponseEqualizerStringParserStereo(
    JamesDSPLib *jdsp,
    const char *masterStr,
    const char *leftStr,
    const char *rightStr)
{
    ArbitraryResponseEqualizerSetMasterCurve(jdsp, masterStr);
    ArbitraryResponseEqualizerSetLeftCurve(jdsp, leftStr);
    ArbitraryResponseEqualizerSetRightCurve(jdsp, rightStr);
}

// Backwards-compatible single-string parser: same curve to all three
void ArbitraryResponseEqualizerStringParser(JamesDSPLib *jdsp, char *stringEq)
{
    ArbitraryResponseEqualizerStringParserStereo(
        jdsp,
        stringEq,  // master curve
        stringEq,  // left curve
        stringEq   // right curve
    );
}

// ----------------------------------------------------------------------------
// [RJDSP-LR-EQ] Flag control helpers for Master / Left / Right
// ----------------------------------------------------------------------------

// Global subsystem on/off (all three curves)

void ArbitraryResponseEqualizerSetGlobalEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp; // not used yet, but kept for symmetry / future
    g_arbEq.enabled = enable ? 1 : 0;
    LOGE("Global ArbEq flag set to %d", g_arbEq.enabled);
}

void ArbitraryResponseEqualizerSetMasterEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.master.enabled = enable ? 1 : 0;
    LOGE("Master flag set to %d", g_arbEq.master.enabled);
}

void ArbitraryResponseEqualizerSetLeftEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.left.enabled = enable ? 1 : 0;
    LOGE("Left flag set to %d", g_arbEq.left.enabled);
}

void ArbitraryResponseEqualizerSetRightEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.right.enabled = enable ? 1 : 0;
    LOGE("Right flag set to %d", g_arbEq.right.enabled);
}
void ArbitraryResponseEqualizerEnable(JamesDSPLib *jdsp, char enable)
{
    if (jdsp->arbMagForceRefresh)
    {
        float *eqFil =
            jdsp->arbMag.instance.coeffGen.GetFilter(
                &jdsp->arbMag.instance.coeffGen,
                (float)jdsp->fs
            );

        float *kDelta = (float*)malloc(
            jdsp->arbMag.instance.filterLen * sizeof(float));
        memset(kDelta, 0,
               jdsp->arbMag.instance.filterLen * sizeof(float));
        kDelta[0] = 1.0f;

        jdsp_lock(jdsp);

        FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
        FFTConvolver2x2Free(&jdsp->arbMag.masterConv);
        FFTConvolver2x2Free(&jdsp->arbMag.leftConv);
        FFTConvolver2x2Free(&jdsp->arbMag.rightConv);

        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.instance.convState,
            (unsigned int)jdsp->blockSize,
            eqFil, eqFil,
            jdsp->arbMag.instance.filterLen
        );

        // Master: EQ on both channels
        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.masterConv,
            (unsigned int)jdsp->blockSize,
            eqFil, eqFil,
            jdsp->arbMag.instance.filterLen
        );

        // Left-only: EQ on L, identity on R
        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.leftConv,
            (unsigned int)jdsp->blockSize,
            eqFil,
            kDelta,
            jdsp->arbMag.instance.filterLen
        );

        // Right-only: identity on L, EQ on R
        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.rightConv,
            (unsigned int)jdsp->blockSize,
            kDelta,
            eqFil,
            jdsp->arbMag.instance.filterLen
        );

        jdsp_unlock(jdsp);
        free(kDelta);

        jdsp->arbMagForceRefresh = 0;
    }

    if (enable)
        jdsp->arbitraryMagEnabled = 1;
}

void ArbitraryResponseEqualizerDisable(JamesDSPLib *jdsp)
{
    jdsp->arbitraryMagEnabled = 0;
}

void ArbitraryResponseEqualizerProcess(JamesDSPLib *jdsp, size_t n)
{
    // If main arbitrary EQ is disabled at jdspController level, this
    // function shouldn't be called, but we keep the check just in case.
    if (!jdsp->arbitraryMagEnabled)
        return;

    LOGE("StereoEQNative: process enabled=%d master=%d left=%d right=%d",
         g_arbEq.enabled,
         g_arbEq.master.enabled,
         g_arbEq.left.enabled,
         g_arbEq.right.enabled);

    if (!g_arbEq.enabled)
        return;

    float *left  = jdsp->tmpBuffer[0];
    float *right = jdsp->tmpBuffer[1];

    // 1) Master curve (stereo)
    if (g_arbEq.master.enabled)
    {
        FFTConvolver2x2Process(
            &jdsp->arbMag.masterConv,
            left, right,
            left, right,
            (unsigned int)n
        );
    }

    // 2) Left-only curve
    if (g_arbEq.left.enabled)
    {
        FFTConvolver2x2Process(
            &jdsp->arbMag.leftConv,
            left, right,
            left, right,
            (unsigned int)n
        );
    }

    // 3) Right-only curve
    if (g_arbEq.right.enabled)
    {
        FFTConvolver2x2Process(
            &jdsp->arbMag.rightConv,
            left, right,
            left, right,
            (unsigned int)n
        );
    }
}